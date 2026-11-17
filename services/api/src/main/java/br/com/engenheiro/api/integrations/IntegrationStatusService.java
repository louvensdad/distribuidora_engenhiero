package br.com.engenheiro.api.integrations;

import br.com.engenheiro.api.finance.openfinance.OpenFinanceProvider;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.ApiException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estado honesto de cada integração externa (F08): CONFIGURADO ou NÃO CONFIGURADO (bloqueado), pendências de
 * triagem e o histórico recente de tentativas. Nunca mostra segredo/credencial.
 */
@Service
public class IntegrationStatusService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final InvoiceService invoices;
    private final PaymentIntegrationService payments;
    private final ObjectProvider<OpenFinanceProvider> openFinance;
    private final IntegrationAttempts attempts;

    public IntegrationStatusService(JdbcClient jdbc, AuthorizationService authz, InvoiceService invoices,
                                    PaymentIntegrationService payments, ObjectProvider<OpenFinanceProvider> openFinance,
                                    IntegrationAttempts attempts) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.invoices = invoices;
        this.payments = payments;
        this.openFinance = openFinance;
        this.attempts = attempts;
    }

    public record Item(String integration, String label, String state, String provider, String message,
                       long pending, String pending_label) {
    }

    public record Status(List<Item> items, List<IntegrationAttempts.AttemptView> recent) {
    }

    @Transactional(readOnly = true)
    public Status status(Actor actor) {
        boolean admin = actor.admin();
        if (!admin && !authz.decide(actor, "invoices", "view").allowed()
                && !authz.decide(actor, "payments", "confirm").allowed()) {
            authz.requireAdmin(actor, "integrations.view"); // audita a negação
            throw ApiException.forbidden();
        }
        FiscalProvider f = invoices.provider();
        PaymentProvider p = payments.provider();
        OpenFinanceProvider of = openFinance.orderedStream().filter(OpenFinanceProvider::configured).findFirst().orElse(null);
        long uncertain = jdbc.sql("SELECT count(*) FROM invoices WHERE organization_id = :o AND state IN "
                + "('TRANSMITTING', 'UNCERTAIN', 'CANCEL_PENDING')").param("o", actor.organizationId())
                .query(Long.class).single();
        long unmatched = jdbc.sql("SELECT count(*) FROM webhook_events WHERE (organization_id = :o OR organization_id IS NULL)"
                + " AND status = 'UNMATCHED'").param("o", actor.organizationId()).query(Long.class).single();
        long ofPending = jdbc.sql("SELECT count(*) FROM bank_transactions WHERE organization_id = :o AND state = 'UNMATCHED'").param("o", actor.organizationId())
                .query(Long.class).single();
        List<Item> items = List.of(
                new Item("FISCAL", "Notas fiscais", f == null ? "NOT_CONFIGURED" : "CONFIGURED", f == null ? null : f.name(),
                        f == null ? "Provedor de emissão e certificado não contratados (D03): emissão BLOQUEADA, nada é "
                                + "transmitido." : "Emissão pelo provedor contratado.", uncertain,
                        "notas com envio/cancelamento em curso ou incerto"),
                new Item("PAYMENTS", "Pagamentos (Pix por provedor)", p == null ? "NOT_CONFIGURED" : "CONFIGURED",
                        p == null ? null : p.name(), p == null ? "Provedor não contratado (D04): cobrança automática "
                                + "BLOQUEADA; vale a conferência manual autorizada." : "Confirmação só por aviso assinado.",
                        unmatched, "avisos para triagem"),
                new Item("OPEN_FINANCE", "Open Finance (extrato bancário)", of == null ? "NOT_CONFIGURED" : "CONFIGURED",
                        of == null ? null : of.name(), of == null ? "Agregador não contratado (ADR-021)."
                                : "Extrato pelo agregador contratado.", ofPending, "transações sem par"));
        return new Status(items, admin ? attempts.recent(actor.organizationId(), null, 50) : List.of());
    }
}
