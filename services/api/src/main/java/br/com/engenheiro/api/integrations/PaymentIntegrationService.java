package br.com.engenheiro.api.integrations;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.orders.PaymentService;
import br.com.engenheiro.api.orders.PaymentService.ProviderPayment;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pagamentos por provedor (F08, ADR-023): cobrança Pix criada no provedor e confirmação SOMENTE por aviso (webhook)
 * com assinatura válida. Regras do dinheiro:
 * - assinatura inválida: registrado como recusado, nada muda no caixa (401);
 * - o mesmo evento (provedor + id do evento) é processado uma única vez: o registro do evento e o efeito no
 *   pagamento acontecem na mesma transação e um índice único impede o segundo (reenvio = DUPLICATE, sem efeito);
 * - valor diferente do cobrado, referência desconhecida ou pagamento já decidido: fica para triagem (UNMATCHED),
 *   nunca vira recebimento automático;
 * - sem provedor: cobrança BLOQUEADA (503) e vale a conferência manual autorizada.
 */
@Service
public class PaymentIntegrationService {
    static final String INTEGRATION = "PAYMENTS";
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final PaymentService payments;
    private final IntegrationAttempts attempts;
    private final ObjectProvider<PaymentProvider> providers;
    private final TransactionTemplate tx;

    public PaymentIntegrationService(JdbcClient jdbc, AuthorizationService authz, PaymentService payments,
                                     IntegrationAttempts attempts, ObjectProvider<PaymentProvider> providers,
                                     PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.payments = payments;
        this.attempts = attempts;
        this.providers = providers;
        this.tx = new TransactionTemplate(txm);
    }

    public record ChargeView(UUID payment_id, String provider, String provider_reference, String code,
                             OffsetDateTime expires_at, String amount, String state) {
    }

    public record WebhookResult(int httpStatus, String status, String detail) {
    }

    public record WebhookEventView(UUID id, String provider, String event_id, boolean signature_valid,
                                   String event_type, String reference, String amount, String status, String detail,
                                   UUID payment_id, OffsetDateTime received_at) {
    }

    PaymentProvider provider() {
        return providers.orderedStream().filter(PaymentProvider::configured).findFirst().orElse(null);
    }

    private PaymentProvider provider(String name) {
        return providers.orderedStream().filter(p -> p.configured() && p.name().equals(name)).findFirst().orElse(null);
    }

    // ---------------- cobrança Pix ----------------
    public ChargeView createCharge(Actor actor, UUID paymentId, String idemKey) {
        IdempotencyService.validateKeyFormat(idemKey);
        ProviderPayment p = tx.execute(t -> payments.lockForCharge(actor, paymentId));
        if (p.chargeCode() != null) {
            return view(p); // já existe cobrança: devolve a mesma (sem criar outra no provedor)
        }
        if (!p.method().equals("PIX") || !p.state().equals("PENDING")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Cobrança só para Pix pendente.");
        }
        PaymentProvider prov = provider();
        if (prov == null) {
            attempts.blocked(actor.organizationId(), INTEGRATION, "charge", "payment", paymentId, idemKey,
                    actor.userId(), "Provedor de pagamentos NÃO CONFIGURADO (D04)");
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Cobrança Pix BLOQUEADA: provedor de pagamentos "
                    + "NÃO CONFIGURADO. Use a conferência manual autorizada; nada foi cobrado.");
        }
        UUID attempt = attempts.start(actor.organizationId(), INTEGRATION, "charge", "payment", paymentId, idemKey,
                actor.userId());
        PaymentProvider.Charge c;
        try {
            // Referência estável por pagamento: repetir não cria segunda cobrança no provedor.
            c = prov.createCharge("pay-" + paymentId, p.amount(), "Pagamento " + paymentId);
        } catch (RuntimeException e) {
            attempts.finish(attempt, "ERROR", "Provedor não criou a cobrança: " + e.getMessage());
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Provedor de pagamentos indisponível; nenhuma "
                    + "cobrança foi criada. Tente de novo ou use a conferência manual.");
        }
        tx.executeWithoutResult(t -> payments.attachCharge(actor, paymentId, prov.name(), c.providerReference(),
                c.code(), c.expiresAt()));
        attempts.finish(attempt, "SUCCESS", "Cobrança criada");
        return view(tx.execute(t -> payments.lockForCharge(actor, paymentId)));
    }

    private static ChargeView view(ProviderPayment p) {
        return new ChargeView(p.id(), p.providerName(), p.providerReference(), p.chargeCode(), p.chargeExpiresAt(),
                Decimals.moneyStr(p.amount()), p.state());
    }

    // ---------------- aviso (webhook) do provedor ----------------
    public WebhookResult receive(String providerName, Map<String, String> headers, byte[] body) {
        PaymentProvider prov = provider(providerName);
        if (prov == null) {
            return new WebhookResult(404, "NOT_CONFIGURED", "Provedor de pagamentos não configurado");
        }
        String sha = sha256(body);
        if (!prov.verify(headers, body)) {
            store(prov.name(), null, false, sha, null, null, null, "REJECTED_SIGNATURE", "Assinatura inválida", null, null);
            return new WebhookResult(401, "REJECTED_SIGNATURE", "Assinatura inválida");
        }
        PaymentProvider.Event e;
        try {
            e = prov.parse(body);
        } catch (RuntimeException ex) {
            store(prov.name(), null, true, sha, null, null, null, "ERROR", "Corpo ilegível", null, null);
            return new WebhookResult(400, "ERROR", "Corpo ilegível");
        }
        if (e.eventId() == null || e.eventId().isBlank()) {
            store(prov.name(), null, true, sha, e.type(), e.providerReference(), e.amount(), "ERROR", "Evento sem id", null, null);
            return new WebhookResult(400, "ERROR", "Evento sem id");
        }
        try {
            return tx.execute(t -> process(prov, e, sha));
        } catch (DuplicateKeyException dup) {
            store(prov.name(), e.eventId(), true, sha, e.type(), e.providerReference(), e.amount(), "DUPLICATE",
                    "Evento já processado (reenvio)", null, null);
            return new WebhookResult(200, "DUPLICATE", "Evento já processado");
        } catch (RuntimeException ex) {
            // Falha nossa (ex.: período fechado): registrado como ERROR e o provedor reenvia depois.
            store(prov.name(), e.eventId(), true, sha, e.type(), e.providerReference(), e.amount(), "ERROR",
                    ex.getMessage(), null, null);
            return new WebhookResult(503, "ERROR", "Não processado agora; reenvie");
        }
    }

    private WebhookResult process(PaymentProvider prov, PaymentProvider.Event e, String sha) {
        int seen = jdbc.sql("""
                        SELECT count(*) FROM webhook_events WHERE provider = :p AND event_id = :e AND signature_valid
                           AND status IN ('PROCESSED', 'UNMATCHED', 'IGNORED')
                        """).param("p", prov.name()).param("e", e.eventId()).query(Integer.class).single();
        if (seen > 0) {
            throw new DuplicateKeyException("evento repetido");
        }
        // Reivindica o evento primeiro (o índice único serializa reenvios simultâneos).
        UUID eventRow = insertEvent(prov.name(), e.eventId(), true, sha, e.type(), e.providerReference(), e.amount(),
                "PROCESSED", null, null, null);
        ProviderPayment p = e.providerReference() == null ? null
                : payments.lockByProviderRef(prov.name(), e.providerReference()).orElse(null);
        String status;
        String detail;
        UUID org = p == null ? null : p.organizationId();
        if (p == null) {
            status = "UNMATCHED";
            detail = "Referência desconhecida";
        } else {
            var system = SystemActors.integration(p.organizationId(), p.branchId(), "webhook:" + prov.name());
            switch (e.type() == null ? "" : e.type()) {
                case "PAYMENT_CONFIRMED" -> {
                    if (e.amount() == null || e.amount().compareTo(p.amount()) != 0) {
                        status = "UNMATCHED";
                        detail = "Valor avisado (" + Decimals.moneyStr(e.amount()) + ") diferente do cobrado ("
                                + Decimals.moneyStr(p.amount()) + ")";
                    } else if (p.state().equals("PENDING")) {
                        payments.confirmByProvider(system, p, e.eventId());
                        status = "PROCESSED";
                        detail = "Pagamento confirmado";
                    } else if (p.state().equals("CONFIRMED")) {
                        status = "IGNORED";
                        detail = "Já estava confirmado (sem novo lançamento)";
                    } else {
                        status = "UNMATCHED";
                        detail = "Pagamento " + p.state() + ": conferir com o provedor";
                    }
                }
                case "PAYMENT_REFUNDED" -> {
                    if (p.state().equals("CONFIRMED")) {
                        if (e.amount() != null && e.amount().compareTo(p.amount()) != 0) {
                            status = "UNMATCHED";
                            detail = "Devolução parcial: conferir e estornar manualmente";
                        } else {
                            payments.reverseByProvider(system, p, e.eventId());
                            status = "PROCESSED";
                            detail = "Pagamento estornado";
                        }
                    } else {
                        status = "IGNORED";
                        detail = "Pagamento " + p.state() + ": nada a estornar";
                    }
                }
                case "PAYMENT_FAILED" -> {
                    if (p.state().equals("PENDING")) {
                        payments.failByProvider(system, p, e.eventId());
                        status = "PROCESSED";
                        detail = "Cobrança não paga";
                    } else {
                        status = "IGNORED";
                        detail = "Pagamento " + p.state();
                    }
                }
                default -> {
                    status = "IGNORED";
                    detail = "Tipo de evento não tratado";
                }
            }
        }
        jdbc.sql("UPDATE webhook_events SET status = :s, detail = :d, organization_id = :o, payment_id = :p WHERE id = :id")
                .param("s", status).param("d", detail).param("o", org).param("p", p == null ? null : p.id())
                .param("id", eventRow).update();
        return new WebhookResult(200, status, detail);
    }

    /** Registro em transação própria (recusas, duplicatas, erros: ficam mesmo sem efeito). */
    private void store(String provider, String eventId, boolean valid, String sha, String type, String ref,
                       BigDecimal amount, String status, String detail, UUID org, UUID payment) {
        tx.executeWithoutResult(t -> insertEvent(provider, eventId, valid, sha, type, ref, amount, status, detail, org, payment));
    }

    /** Registro na transação corrente (o evento processado e seu efeito confirmam juntos). */
    private UUID insertEvent(String provider, String eventId, boolean valid, String sha, String type, String ref,
                             BigDecimal amount, String status, String detail, UUID org, UUID payment) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO webhook_events (id, provider, event_id, signature_valid, payload_sha256, event_type,
                                                    reference, amount, status, detail, organization_id, payment_id)
                        VALUES (:id, :p, :e, :v, :sha, :t, :r, :a, :s, :d, :o, :pay)
                        """)
                .param("id", id).param("p", provider).param("e", cut(eventId, 120)).param("v", valid).param("sha", sha)
                .param("t", cut(type, 60)).param("r", cut(ref, 120)).param("a", amount).param("s", status)
                .param("d", cut(detail, 500)).param("o", org).param("pay", payment).update();
        return id;
    }

    // ---------------- triagem ----------------
    @Transactional(readOnly = true)
    public List<WebhookEventView> events(Actor actor, String status) {
        authz.requireAdmin(actor, "integrations.view");
        return jdbc.sql("""
                        SELECT id, provider, event_id, signature_valid, event_type, reference, amount, status, detail,
                               payment_id, received_at
                          FROM webhook_events
                         WHERE (organization_id = :o OR organization_id IS NULL)
                           AND (CAST(:s AS text) IS NULL OR status = :s)
                         ORDER BY received_at DESC LIMIT 200
                        """).param("o", actor.organizationId()).param("s", status)
                .query((rs, i) -> new WebhookEventView(rs.getObject("id", UUID.class), rs.getString("provider"),
                        rs.getString("event_id"), rs.getBoolean("signature_valid"), rs.getString("event_type"),
                        rs.getString("reference"), Decimals.moneyStr(rs.getBigDecimal("amount")), rs.getString("status"),
                        rs.getString("detail"), rs.getObject("payment_id", UUID.class),
                        rs.getObject("received_at", OffsetDateTime.class)))
                .list();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b == null ? new byte[0] : b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
