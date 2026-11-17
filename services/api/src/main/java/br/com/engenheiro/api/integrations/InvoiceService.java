package br.com.engenheiro.api.integrations;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.documents.DocumentService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notas fiscais (F08, ADR-023). Estados distintos: rascunho → conferida pelo contador → transmitindo → autorizada |
 * rejeitada | incerta; cancelamento é fluxo separado (cancelamento pendente → cancelada).
 *
 * Regras de segurança da emissão:
 * - o envio ao provedor acontece FORA da transação do banco: primeiro a nota passa a TRANSMITTING (um único envio
 *   vence a corrida), depois chama o provedor, depois grava o resultado;
 * - a referência enviada é estável por nota ({@code inv-<id>}), então o provedor nunca cria duas notas para ela;
 * - tempo esgotado = INCERTA: a nota não pode ser reenviada, só consultada; se o provedor responder que nunca recebeu,
 *   ela volta a "conferida" e pode ser transmitida de novo com a mesma referência;
 * - sem provedor/tipo fiscal/dados do emitente: BLOQUEADO (503), tentativa registrada, nada simulado.
 * Permissões (contrato): ver `invoices.view`; criar/descartar rascunho `invoices.create`; conferir (contador)
 * `invoices.request`; transmitir/consultar `invoices.confirm`; cancelar `invoices.cancel`; baixar XML `invoices.export`.
 */
@Service
public class InvoiceService {
    static final String INTEGRATION = "FISCAL";
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final DocumentService documents;
    private final IntegrationAttempts attempts;
    private final ObjectProvider<FiscalProvider> providers;
    private final TransactionTemplate tx;

    public InvoiceService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                          DocumentService documents, IntegrationAttempts attempts,
                          ObjectProvider<FiscalProvider> providers, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.documents = documents;
        this.attempts = attempts;
        this.providers = providers;
        this.tx = new TransactionTemplate(txm);
    }

    // ---------------- DTOs ----------------
    public record SettingsCommand(String document_type, String environment, Integer series, String default_cfop,
                                  String issuer_document, String state_registration, String tax_regime, String notes) {
    }

    public record SettingsView(String document_type, String environment, Integer series, String default_cfop,
                               String issuer_document, String state_registration, String tax_regime, String notes,
                               String provider_state, String provider, List<String> missing, long version) {
    }

    public record CreateCommand(UUID order_id) {
    }

    public record ReasonCommand(String reason) {
    }

    public record LineView(int line_no, UUID product_id, String description, String ncm, String cfop, String unit,
                           String quantity, String unit_price, String total) {
    }

    public record InvoiceView(UUID id, UUID order_id, long order_number, String customer_name, String document_type,
                              String environment, String state, String total, String provider, Long number,
                              Integer series, String access_key, String protocol, UUID xml_document_id,
                              String rejection_code, String rejection_reason, String cancel_reason,
                              String cancel_protocol, int attempts, String reviewed_by_name, OffsetDateTime reviewed_at,
                              OffsetDateTime transmitted_at, OffsetDateTime authorized_at, String created_by_name,
                              OffsetDateTime created_at, List<LineView> lines, List<String> issues,
                              String provider_state, List<IntegrationAttempts.AttemptView> history, long version) {
    }

    record IRow(UUID id, UUID organizationId, UUID branchId, UUID orderId, long orderNumber, String customerName,
                UUID customerId, String documentType, String environment, String state, BigDecimal total,
                String provider, String externalRef, Long number, Integer series, String accessKey, String protocol,
                UUID xmlDocumentId, String rejectionCode, String rejectionReason, String cancelReason,
                String cancelProtocol, int attempts, String reviewedByName, OffsetDateTime reviewedAt,
                OffsetDateTime transmittedAt, OffsetDateTime authorizedAt, String createdByName,
                OffsetDateTime createdAt, long version) {
    }

    record Settings(String documentType, String environment, Integer series, String defaultCfop, String issuerDocument,
                    String stateRegistration, String taxRegime, String notes, long version) {
    }

    // ---------------- configuração fiscal (definida com o contador) ----------------
    @Transactional(readOnly = true)
    public SettingsView settings(Actor actor) {
        authz.require(actor, "invoices", "view");
        return settingsView(actor.organizationId());
    }

    @Transactional
    public SettingsView updateSettings(Actor actor, long expectedVersion, SettingsCommand c) {
        authz.requireAdmin(actor, "fiscal.settings");
        List<FieldError> errors = new ArrayList<>();
        String type = Text.trim(c.document_type());
        if (type != null && !List.of("NFE", "NFCE", "BOTH").contains(type)) {
            errors.add(new FieldError("document_type", "Use NFE, NFCE ou BOTH (confirme com o contador)"));
        }
        String env = Text.trim(c.environment()) == null ? "HOMOLOGACAO" : c.environment().strip();
        if (!List.of("HOMOLOGACAO", "PRODUCAO").contains(env)) {
            errors.add(new FieldError("environment", "Use HOMOLOGACAO ou PRODUCAO"));
        }
        if (c.series() != null && (c.series() < 1 || c.series() > 999)) {
            errors.add(new FieldError("series", "Série de 1 a 999"));
        }
        String cfop = Text.document(c.default_cfop());
        if (cfop != null && !cfop.matches("[1-7][0-9]{3}")) {
            errors.add(new FieldError("default_cfop", "CFOP com 4 dígitos (ex.: 5102)"));
        }
        String cnpj = Text.document(c.issuer_document());
        if (cnpj != null && !br.com.engenheiro.api.finance.openfinance.OpenFinanceService.validCnpj(cnpj)) {
            errors.add(new FieldError("issuer_document", "CNPJ inválido"));
        }
        String ie = Text.trim(c.state_registration());
        if (ie != null && (ie.length() < 2 || ie.length() > 20)) {
            errors.add(new FieldError("state_registration", "Inscrição estadual inválida"));
        }
        String regime = Text.trim(c.tax_regime());
        if (regime != null && !List.of("SIMPLES_NACIONAL", "SIMPLES_EXCESSO", "NORMAL").contains(regime)) {
            errors.add(new FieldError("tax_regime", "Use SIMPLES_NACIONAL, SIMPLES_EXCESSO ou NORMAL"));
        }
        String notes = Text.trim(c.notes());
        if (notes != null && notes.length() > 500) {
            errors.add(new FieldError("notes", "Até 500 caracteres"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija a configuração fiscal.", errors);
        }
        Optional<Settings> cur = loadSettings(actor.organizationId());
        long current = cur.map(Settings::version).orElse(0L);
        if (current != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A configuração foi alterada. Recarregue.");
        }
        jdbc.sql("""
                        INSERT INTO fiscal_settings (organization_id, document_type, environment, series, default_cfop,
                                                     issuer_document, state_registration, tax_regime, notes, updated_by)
                        VALUES (:o, :t, :e, :s, :c, :d, :ie, :r, :n, :by)
                        ON CONFLICT (organization_id) DO UPDATE SET document_type = :t, environment = :e, series = :s,
                               default_cfop = :c, issuer_document = :d, state_registration = :ie, tax_regime = :r,
                               notes = :n, updated_by = :by, updated_at = now(), version = fiscal_settings.version + 1
                        """)
                .param("o", actor.organizationId()).param("t", type).param("e", env).param("s", c.series())
                .param("c", cfop).param("d", cnpj).param("ie", ie).param("r", regime).param("n", notes)
                .param("by", actor.userId()).update();
        Map<String, Object> ch = new HashMap<>();
        ch.put("document_type", type);
        ch.put("environment", env);
        ch.put("series", c.series());
        ch.put("default_cfop", cfop);
        ch.put("tax_regime", regime);
        audit.record(entry(actor, "fiscal.settings", "fiscal_settings", actor.organizationId(), ch));
        return settingsView(actor.organizationId());
    }

    private SettingsView settingsView(UUID org) {
        Settings s = loadSettings(org).orElse(new Settings(null, "HOMOLOGACAO", null, null, null, null, null, null, 0));
        FiscalProvider p = provider();
        return new SettingsView(s.documentType(), s.environment(), s.series(), s.defaultCfop(), s.issuerDocument(),
                s.stateRegistration(), s.taxRegime(), s.notes(), p == null ? "NOT_CONFIGURED" : "CONFIGURED",
                p == null ? null : p.name(), settingsMissing(s), s.version());
    }

    private static List<String> settingsMissing(Settings s) {
        List<String> m = new ArrayList<>();
        if (s.documentType() == null) {
            m.add("Tipo de documento fiscal (NF-e/NFC-e) — definir com o contador");
        }
        if (s.issuerDocument() == null) {
            m.add("CNPJ do emitente");
        }
        if (s.stateRegistration() == null) {
            m.add("Inscrição estadual");
        }
        if (s.taxRegime() == null) {
            m.add("Regime tributário");
        }
        if (s.series() == null) {
            m.add("Série");
        }
        if (s.defaultCfop() == null) {
            m.add("CFOP padrão de venda");
        }
        return m;
    }

    private Optional<Settings> loadSettings(UUID org) {
        return jdbc.sql("""
                        SELECT document_type, environment, series, default_cfop, issuer_document, state_registration,
                               tax_regime, notes, version FROM fiscal_settings WHERE organization_id = :o
                        """).param("o", org).query(Settings.class).optional();
    }

    FiscalProvider provider() {
        return providers.orderedStream().filter(FiscalProvider::configured).findFirst().orElse(null);
    }

    // ---------------- dados fiscais do produto (informados pelo contador) ----------------
    public record ProductFiscalCommand(String ncm, Integer fiscal_origin) {
    }

    public record ProductFiscalView(UUID product_id, String name, String ncm, Integer fiscal_origin) {
    }

    @Transactional
    public ProductFiscalView updateProductFiscal(Actor actor, UUID productId, ProductFiscalCommand c) {
        authz.require(actor, "invoices", "request");
        List<FieldError> errors = new ArrayList<>();
        String ncm = c == null ? null : Text.document(c.ncm());
        if (ncm == null || !ncm.matches("[0-9]{8}")) {
            errors.add(new FieldError("ncm", "NCM com 8 dígitos (informado pelo contador)"));
        }
        Integer origin = c == null || c.fiscal_origin() == null ? 0 : c.fiscal_origin();
        if (origin < 0 || origin > 8) {
            errors.add(new FieldError("fiscal_origin", "Origem de 0 a 8"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os dados fiscais do produto.", errors);
        }
        int n = jdbc.sql("UPDATE products SET ncm = :n, fiscal_origin = :o WHERE id = :id AND organization_id = :org")
                .param("n", ncm).param("o", origin).param("id", productId).param("org", actor.organizationId()).update();
        if (n == 0) {
            throw ApiException.notFound();
        }
        // Rascunhos em aberto passam a mostrar o NCM corrigido (nota conferida/transmitida não muda).
        jdbc.sql("""
                        UPDATE invoice_lines l SET ncm = :n FROM invoices i
                         WHERE i.id = l.invoice_id AND i.state = 'DRAFT' AND l.product_id = :p AND i.organization_id = :org
                        """).param("n", ncm).param("p", productId).param("org", actor.organizationId()).update();
        audit.record(entry(actor, "products.fiscal_data", "product", productId, Map.of("ncm", ncm, "fiscal_origin", origin)));
        return jdbc.sql("SELECT id AS product_id, name, ncm, fiscal_origin FROM products WHERE id = :id")
                .param("id", productId).query(ProductFiscalView.class).single();
    }

    // ---------------- rascunho a partir do pedido ----------------
    @Transactional
    public InvoiceView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, "invoices", "create");
        if (cmd == null || cmd.order_id() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o pedido.",
                    List.of(new FieldError("order_id", "Obrigatório")));
        }
        // Nota é de escopo da organização (contrato: invoices ORGANIZATION): o contador emite sem ver a operação do
        // pedido; a nota mostra só itens, valores e o nome do cliente. Pedido de outra organização = 404.
        return idem.execute(actor, "invoices.create", idemKey, Map.of("order", cmd.order_id().toString()),
                InvoiceView.class, () -> {
                    record O(UUID id, UUID organizationId, UUID branchId, String state, String customerDocument) {
                    }
                    O o = jdbc.sql("""
                                    SELECT o.id, o.organization_id, o.branch_id, o.state, c.personal_document AS customer_document
                                      FROM orders o JOIN customers c ON c.id = o.customer_id
                                     WHERE o.id = :id AND o.organization_id = :org FOR UPDATE OF o
                                    """)
                            .param("id", cmd.order_id()).param("org", actor.organizationId()).query(O.class).optional()
                            .orElseThrow(ApiException::notFound);
                    if (o.state().equals("CANCELLED")) {
                        throw new ApiException(ErrorCode.INVALID_TRANSITION, "Pedido cancelado não gera nota.");
                    }
                    Settings s = loadSettings(actor.organizationId()).orElse(null);
                    if (s == null || s.documentType() == null) {
                        attempts.blocked(actor.organizationId(), INTEGRATION, "draft", "order", o.id(), idemKey,
                                actor.userId(), "Tipo de documento fiscal não definido");
                        throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Emissão BLOQUEADA: o tipo de documento "
                                + "fiscal (NF-e/NFC-e) ainda não foi definido com o contador (configuração fiscal).");
                    }
                    String type = s.documentType().equals("BOTH")
                            ? (o.customerDocument() != null && o.customerDocument().length() == 14 ? "NFE" : "NFCE")
                            : s.documentType();
                    int live = jdbc.sql("""
                                    SELECT count(*) FROM invoices WHERE order_id = :o AND state IN ('DRAFT', 'READY',
                                           'TRANSMITTING', 'UNCERTAIN', 'AUTHORIZED', 'CANCEL_PENDING')
                                    """).param("o", o.id()).query(Integer.class).single();
                    if (live > 0) {
                        throw new ApiException(ErrorCode.INVALID_TRANSITION, "Este pedido já tem nota em andamento ou autorizada.");
                    }
                    record L(int lineNo, UUID productId, String name, String unit, BigDecimal factor,
                             BigDecimal baseQuantity, BigDecimal effBase, BigDecimal net) {
                    }
                    List<L> lines = jdbc.sql("""
                                    SELECT l.line_no, l.product_id, p.name, l.unit, l.factor, l.base_quantity,
                                           l.base_quantity - l.cancelled_base_qty - l.returned_base_qty AS eff_base, l.net
                                      FROM order_lines l JOIN products p ON p.id = l.product_id
                                     WHERE l.order_id = :o ORDER BY l.line_no
                                    """).param("o", o.id()).query(L.class).list();
                    UUID id = Ids.newId();
                    BigDecimal total = BigDecimal.ZERO.setScale(2);
                    List<Object[]> rows = new ArrayList<>();
                    int n = 0;
                    for (L l : lines) {
                        if (l.effBase().signum() <= 0) {
                            continue;
                        }
                        BigDecimal qty = l.effBase().divide(l.factor(), Decimals.QTY_SCALE, RoundingMode.HALF_EVEN);
                        BigDecimal lineTotal = l.net().multiply(l.effBase())
                                .divide(l.baseQuantity(), 2, RoundingMode.HALF_EVEN);
                        BigDecimal unitPrice = lineTotal.divide(qty, Decimals.QTY_SCALE, RoundingMode.HALF_EVEN);
                        total = total.add(lineTotal);
                        rows.add(new Object[]{++n, l.productId(), l.name(), l.unit(), qty, unitPrice, lineTotal});
                    }
                    if (rows.isEmpty() || total.signum() <= 0) {
                        throw new ApiException(ErrorCode.INVALID_TRANSITION, "Pedido sem itens com valor para a nota.");
                    }
                    jdbc.sql("""
                                    INSERT INTO invoices (id, organization_id, branch_id, order_id, document_type, environment,
                                                          total, series, created_by)
                                    VALUES (:id, :o, :b, :ord, :t, :e, :tot, :s, :by)
                                    """)
                            .param("id", id).param("o", actor.organizationId()).param("b", o.branchId())
                            .param("ord", o.id()).param("t", type).param("e", s.environment()).param("tot", total)
                            .param("s", s.series()).param("by", actor.userId()).update();
                    for (Object[] r : rows) {
                        jdbc.sql("""
                                        INSERT INTO invoice_lines (id, invoice_id, line_no, product_id, description, unit,
                                                                   quantity, unit_price, total)
                                        VALUES (:id, :inv, :n, :p, :d, :u, :q, :up, :t)
                                        """)
                                .param("id", Ids.newId()).param("inv", id).param("n", r[0]).param("p", r[1])
                                .param("d", r[2]).param("u", r[3]).param("q", r[4]).param("up", r[5]).param("t", r[6])
                                .update();
                    }
                    refreshFiscalData(id, s);
                    audit.record(entry(actor, "invoices.create", "invoice", id, Map.of("order_id", o.id().toString(),
                            "document_type", type, "total", total.toPlainString())));
                    return view(actor, find(id).orElseThrow());
                });
    }

    /** NCM/CFOP vêm do cadastro do produto e da configuração (informados pelo contador), atualizados até a conferência. */
    private void refreshFiscalData(UUID invoiceId, Settings s) {
        jdbc.sql("""
                        UPDATE invoice_lines l SET ncm = p.ncm, cfop = :cfop
                          FROM products p WHERE p.id = l.product_id AND l.invoice_id = :id
                        """).param("cfop", s.defaultCfop()).param("id", invoiceId).update();
    }

    private List<String> issues(IRow r, List<LineView> lines) {
        List<String> m = new ArrayList<>(loadSettings(r.organizationId()).map(InvoiceService::settingsMissing)
                .orElse(List.of("Configuração fiscal")));
        m.remove("Tipo de documento fiscal (NF-e/NFC-e) — definir com o contador"); // já definido para criar
        for (LineView l : lines) {
            if (l.ncm() == null) {
                m.add("Item " + l.line_no() + " (" + l.description() + "): NCM não cadastrado no produto");
            }
        }
        if (r.documentType().equals("NFE")) {
            String doc = jdbc.sql("SELECT personal_document FROM customers WHERE id = :c").param("c", r.customerId())
                    .query(String.class).optional().orElse(null);
            if (doc == null) {
                m.add("NF-e exige CPF/CNPJ do cliente");
            }
        }
        return m;
    }

    // ---------------- conferência do contador ----------------
    @Transactional
    public InvoiceView review(Actor actor, UUID id, long expectedVersion) {
        authz.require(actor, "invoices", "request");
        IRow r = lock(actor, id);
        if (!r.state().equals("DRAFT")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só rascunho pode ser conferido.");
        }
        checkVersion(r, expectedVersion);
        refreshFiscalData(id, loadSettings(r.organizationId()).orElseThrow());
        List<String> pending = issues(r, lines(id));
        if (!pending.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Faltam dados fiscais: " + String.join("; ", pending),
                    pending.stream().map(p -> new FieldError("fiscal", p)).toList());
        }
        jdbc.sql("""
                        UPDATE invoices SET state = 'READY', reviewed_by = :by, reviewed_at = now(), version = version + 1
                         WHERE id = :id
                        """).param("by", actor.userId()).param("id", id).update();
        audit.record(entry(actor, "invoices.review", "invoice", id, Map.of("total", r.total().toPlainString())));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional
    public InvoiceView discard(Actor actor, UUID id, long expectedVersion, ReasonCommand cmd) {
        authz.require(actor, "invoices", "create");
        String reason = cmd == null ? null : Text.trim(cmd.reason());
        if (reason == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("reason", "Obrigatório")));
        }
        IRow r = lock(actor, id);
        if (!List.of("DRAFT", "READY", "REJECTED").contains(r.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Nota " + r.state() + " não pode ser descartada.");
        }
        checkVersion(r, expectedVersion);
        jdbc.sql("UPDATE invoices SET state = 'DISCARDED', version = version + 1 WHERE id = :id").param("id", id).update();
        audit.record(entry(actor, "invoices.discard", "invoice", id, Map.of("reason", reason)));
        return view(actor, find(id).orElseThrow());
    }

    // ---------------- transmissão ----------------
    public InvoiceView transmit(Actor actor, UUID id, String idemKey, long expectedVersion) {
        authz.require(actor, "invoices", "confirm");
        IdempotencyService.validateKeyFormat(idemKey);
        IRow r = tx.execute(t -> lock(actor, id));
        // Repetição do mesmo comando (mesma chave) depois de já enviado: devolve o estado atual, sem reenviar.
        if (!r.state().equals("READY") && attempts.lastKey("invoice", id, "transmit").filter(idemKey::equals).isPresent()) {
            return getView(actor, id);
        }
        if (r.state().equals("UNCERTAIN") || r.state().equals("TRANSMITTING")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Resultado do envio anterior ainda incerto: consulte o "
                    + "provedor antes (nunca se reenvia sem saber se a nota já foi emitida).");
        }
        if (!r.state().equals("READY")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só nota conferida pelo contador pode ser transmitida.");
        }
        checkVersion(r, expectedVersion);
        FiscalProvider p = provider();
        Settings s = loadSettings(r.organizationId()).orElse(null);
        String blockedWhy = p == null ? "Provedor fiscal NÃO CONFIGURADO (D03)"
                : !p.supports(r.documentType()) ? "Provedor não emite " + r.documentType()
                : s == null || !settingsMissing(s).isEmpty() ? "Configuração fiscal incompleta" : null;
        if (blockedWhy != null) {
            attempts.blocked(r.organizationId(), INTEGRATION, "transmit", "invoice", id, idemKey, actor.userId(), blockedWhy);
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Emissão BLOQUEADA: " + blockedWhy
                    + ". Nada foi enviado nem emitido.");
        }
        FiscalProvider.Payload payload = tx.execute(t -> {
            int n = jdbc.sql("""
                            UPDATE invoices SET state = 'TRANSMITTING', provider = :p, attempts = attempts + 1,
                                   transmitted_by = :by, transmitted_at = now(), rejection_code = NULL,
                                   rejection_reason = NULL, version = version + 1
                             WHERE id = :id AND state = 'READY' AND version = :v
                            """)
                    .param("p", p.name()).param("by", actor.userId()).param("id", id).param("v", expectedVersion).update();
            if (n == 0) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "A nota foi alterada ou já está sendo enviada. Recarregue.");
            }
            audit.record(entry(actor, "invoices.transmit", "invoice", id, Map.of("provider", p.name(),
                    "document_type", r.documentType(), "total", r.total().toPlainString())));
            return payload(r, s);
        });
        UUID attempt = attempts.start(r.organizationId(), INTEGRATION, "transmit", "invoice", id, idemKey, actor.userId());
        FiscalProvider.Result result;
        try {
            result = p.submit(payload);
        } catch (FiscalProvider.Timeout e) {
            attempts.finish(attempt, "TIMEOUT", "Sem resposta do provedor: resultado incerto");
            tx.executeWithoutResult(t -> jdbc.sql("UPDATE invoices SET state = 'UNCERTAIN', version = version + 1 "
                    + "WHERE id = :id AND state = 'TRANSMITTING'").param("id", id).update());
            return getView(actor, id);
        } catch (FiscalProvider.Unavailable e) {
            attempts.finish(attempt, "ERROR", "Provedor indisponível; nada foi enviado: " + e.getMessage());
            tx.executeWithoutResult(t -> jdbc.sql("UPDATE invoices SET state = 'READY', version = version + 1 "
                    + "WHERE id = :id AND state = 'TRANSMITTING'").param("id", id).update());
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Provedor fiscal indisponível agora; a nota não "
                    + "foi enviada e continua conferida. Tente de novo mais tarde.");
        } catch (RuntimeException e) {
            // Erro desconhecido depois de chamar: tratar como incerto (pode ter emitido).
            attempts.finish(attempt, "ERROR", "Erro inesperado no envio: resultado incerto");
            tx.executeWithoutResult(t -> jdbc.sql("UPDATE invoices SET state = 'UNCERTAIN', version = version + 1 "
                    + "WHERE id = :id AND state = 'TRANSMITTING'").param("id", id).update());
            return getView(actor, id);
        }
        applyResult(actor, id, attempt, result);
        return getView(actor, id);
    }

    /** Consulta o provedor sobre nota transmitindo/incerta. Nunca reenvia por conta própria. */
    public InvoiceView resolve(Actor actor, UUID id) {
        authz.require(actor, "invoices", "confirm");
        IRow r = tx.execute(t -> lock(actor, id));
        if (!List.of("TRANSMITTING", "UNCERTAIN").contains(r.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só nota com envio em curso ou incerto é consultada.");
        }
        resolveRow(actor, r);
        return getView(actor, id);
    }

    /** Job: consulta automaticamente as notas incertas/em curso (identidade técnica de integração). */
    public int resolvePending() {
        if (provider() == null) {
            return 0;
        }
        record P(UUID id, UUID organizationId, UUID branchId) {
        }
        List<P> pending = jdbc.sql("""
                        SELECT id, organization_id, branch_id FROM invoices
                         WHERE state IN ('TRANSMITTING', 'UNCERTAIN') AND transmitted_at < now() - interval '1 minute'
                         ORDER BY transmitted_at LIMIT 50
                        """).query(P.class).list();
        int done = 0;
        for (P p : pending) {
            Actor sys = SystemActors.integration(p.organizationId(), p.branchId(), "job:fiscal");
            IRow r = find(p.id()).orElseThrow();
            if (resolveRow(sys, r)) {
                done++;
            }
        }
        return done;
    }

    private boolean resolveRow(Actor actor, IRow r) {
        FiscalProvider p = provider();
        String key = "resolve-" + Ids.newId();
        if (p == null) {
            attempts.blocked(r.organizationId(), INTEGRATION, "query", "invoice", r.id(), key, actor.userId(),
                    "Provedor fiscal NÃO CONFIGURADO");
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Provedor fiscal NÃO CONFIGURADO; não há como consultar.");
        }
        UUID attempt = attempts.start(r.organizationId(), INTEGRATION, "query", "invoice", r.id(), key, actor.userId());
        FiscalProvider.Result result;
        try {
            result = p.query(reference(r.id()));
        } catch (RuntimeException e) {
            attempts.finish(attempt, e instanceof FiscalProvider.Timeout ? "TIMEOUT" : "ERROR",
                    "Consulta sem resposta; continua incerta");
            return false;
        }
        if ("NOT_FOUND".equals(result.status())) {
            attempts.finish(attempt, "SUCCESS", "Provedor não recebeu a nota: volta a conferida (pode transmitir de novo)");
            tx.executeWithoutResult(t -> {
                jdbc.sql("UPDATE invoices SET state = 'READY', version = version + 1 WHERE id = :id "
                        + "AND state IN ('TRANSMITTING', 'UNCERTAIN')").param("id", r.id()).update();
                audit.record(entry(actor, "invoices.resolve", "invoice", r.id(), Map.of("result", "NOT_FOUND")));
            });
            return true;
        }
        applyResult(actor, r.id(), attempt, result);
        return !"PROCESSING".equals(result.status());
    }

    private void applyResult(Actor actor, UUID id, UUID attempt, FiscalProvider.Result res) {
        switch (res.status() == null ? "" : res.status()) {
            case "AUTHORIZED" -> {
                if (res.accessKey() == null || !res.accessKey().matches("[0-9]{44}") || res.protocol() == null
                        || res.xml() == null || res.xml().length == 0) {
                    attempts.finish(attempt, "ERROR", "Resposta de autorização incompleta (chave/protocolo/XML)");
                    markUncertain(id);
                    return;
                }
                try {
                    storeAuthorized(actor, id, res);
                } catch (RuntimeException e) {
                    attempts.finish(attempt, "ERROR", "Autorizada no provedor, mas o XML não pôde ser guardado ("
                            + e.getMessage() + "): continua incerta até nova consulta");
                    markUncertain(id);
                    return;
                }
                attempts.finish(attempt, "SUCCESS", "Autorizada: protocolo " + res.protocol());
            }
            case "REJECTED" -> {
                String reason = res.rejectionReason() == null ? "Rejeitada sem motivo informado" : res.rejectionReason();
                tx.executeWithoutResult(t -> {
                    jdbc.sql("""
                                    UPDATE invoices SET state = 'REJECTED', rejection_code = :c, rejection_reason = :r,
                                           version = version + 1 WHERE id = :id AND state IN ('TRANSMITTING', 'UNCERTAIN')
                                    """)
                            .param("c", res.rejectionCode()).param("r", reason.length() > 500 ? reason.substring(0, 500) : reason)
                            .param("id", id).update();
                    Map<String, Object> ch = new HashMap<>();
                    ch.put("code", res.rejectionCode());
                    ch.put("reason", reason);
                    audit.record(entry(actor, "invoices.rejected", "invoice", id, ch));
                });
                attempts.finish(attempt, "REJECTED", (res.rejectionCode() == null ? "" : res.rejectionCode() + " ") + reason);
            }
            case "PROCESSING" -> {
                attempts.finish(attempt, "SENT", "Recebida pelo provedor, aguardando a SEFAZ");
                tx.executeWithoutResult(t -> jdbc.sql("UPDATE invoices SET state = 'TRANSMITTING', version = version + 1 "
                        + "WHERE id = :id AND state = 'UNCERTAIN'").param("id", id).update());
            }
            default -> {
                attempts.finish(attempt, "ERROR", "Resposta desconhecida do provedor: tratada como incerta");
                markUncertain(id);
            }
        }
    }

    private void storeAuthorized(Actor actor, UUID id, FiscalProvider.Result res) {
                tx.executeWithoutResult(t -> {
                    IRow r = find(id).orElseThrow();
                    var doc = documents.attach(actorFor(actor, r), r.branchId(), "invoice", id,
                            "nota-" + res.accessKey() + ".xml", res.xml());
                    if (!doc.available()) {
                        throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "XML devolvido pelo provedor foi recusado: "
                                + doc.rejection());
                    }
                    jdbc.sql("""
                                    UPDATE invoices SET state = 'AUTHORIZED', external_ref = :ext, number = :n, series = :s,
                                           access_key = :k, protocol = :pr, xml_document_id = :doc, authorized_at = now(),
                                           version = version + 1
                                     WHERE id = :id AND state IN ('TRANSMITTING', 'UNCERTAIN')
                                    """)
                            .param("ext", res.externalRef()).param("n", res.number()).param("s", res.series())
                            .param("k", res.accessKey()).param("pr", res.protocol()).param("doc", doc.id())
                            .param("id", id).update();
                    audit.record(entry(actor, "invoices.authorized", "invoice", id, Map.of("access_key", res.accessKey(),
                            "protocol", res.protocol())));
                });
    }

    private void markUncertain(UUID id) {
        tx.executeWithoutResult(t -> jdbc.sql("UPDATE invoices SET state = 'UNCERTAIN', version = version + 1 "
                + "WHERE id = :id AND state = 'TRANSMITTING'").param("id", id).update());
    }

    // ---------------- cancelamento (fluxo separado) ----------------
    public InvoiceView cancel(Actor actor, UUID id, String idemKey, long expectedVersion, ReasonCommand cmd) {
        authz.require(actor, "invoices", "cancel");
        IdempotencyService.validateKeyFormat(idemKey);
        String reason = cmd == null ? null : Text.trim(cmd.reason());
        if (reason == null || reason.length() < 15 || reason.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Justificativa do cancelamento: de 15 a 255 caracteres.",
                    List.of(new FieldError("reason", "De 15 a 255 caracteres (exigência da SEFAZ)")));
        }
        IRow r = tx.execute(t -> lock(actor, id));
        if (r.state().equals("CANCELLED")) {
            return getView(actor, id);
        }
        if (!List.of("AUTHORIZED", "CANCEL_PENDING").contains(r.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só nota autorizada pode ser cancelada.");
        }
        if (r.state().equals("AUTHORIZED")) {
            checkVersion(r, expectedVersion);
        }
        FiscalProvider p = provider();
        if (p == null) {
            attempts.blocked(r.organizationId(), INTEGRATION, "cancel", "invoice", id, idemKey, actor.userId(),
                    "Provedor fiscal NÃO CONFIGURADO");
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Cancelamento BLOQUEADO: provedor fiscal NÃO "
                    + "CONFIGURADO. A nota continua autorizada.");
        }
        tx.executeWithoutResult(t -> {
            int n = jdbc.sql("""
                            UPDATE invoices SET state = 'CANCEL_PENDING', cancel_reason = :r, version = version + 1
                             WHERE id = :id AND state IN ('AUTHORIZED', 'CANCEL_PENDING')
                            """).param("r", reason).param("id", id).update();
            if (n == 0) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "A nota foi alterada. Recarregue.");
            }
            audit.record(entry(actor, "invoices.cancel", "invoice", id, Map.of("reason", reason)));
        });
        UUID attempt = attempts.start(r.organizationId(), INTEGRATION, "cancel", "invoice", id, idemKey, actor.userId());
        FiscalProvider.CancelResult res;
        try {
            res = p.cancel(reference(id), r.accessKey(), reason);
        } catch (RuntimeException e) {
            attempts.finish(attempt, e instanceof FiscalProvider.Timeout ? "TIMEOUT" : "ERROR",
                    "Cancelamento sem confirmação; continua pendente (repita para consultar)");
            return getView(actor, id);
        }
        switch (res.status() == null ? "" : res.status()) {
            case "CANCELLED" -> {
                tx.executeWithoutResult(t -> {
                    jdbc.sql("""
                                    UPDATE invoices SET state = 'CANCELLED', cancel_protocol = :p, version = version + 1
                                     WHERE id = :id AND state = 'CANCEL_PENDING'
                                    """).param("p", res.protocol()).param("id", id).update();
                    audit.record(entry(actor, "invoices.cancelled", "invoice", id, Map.of("protocol",
                            String.valueOf(res.protocol()))));
                });
                attempts.finish(attempt, "SUCCESS", "Cancelada: protocolo " + res.protocol());
            }
            case "REJECTED" -> {
                tx.executeWithoutResult(t -> jdbc.sql("""
                                UPDATE invoices SET state = 'AUTHORIZED', version = version + 1
                                 WHERE id = :id AND state = 'CANCEL_PENDING'
                                """).param("id", id).update());
                attempts.finish(attempt, "REJECTED", "Cancelamento recusado: " + res.reason());
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Cancelamento recusado pela SEFAZ: " + res.reason()
                        + ". A nota continua autorizada.");
            }
            default -> attempts.finish(attempt, "SENT", "Cancelamento em processamento");
        }
        return getView(actor, id);
    }

    // ---------------- consultas ----------------
    @Transactional(readOnly = true)
    public List<InvoiceView> list(Actor actor, String state) {
        authz.require(actor, "invoices", "view");
        return jdbc.sql(SELECT + " WHERE i.organization_id = :o AND (CAST(:s AS text) IS NULL OR i.state = :s)"
                        + " ORDER BY i.created_at DESC LIMIT 200")
                .param("o", actor.organizationId()).param("s", Text.trim(state)).query(IRow.class).list().stream()
                .map(r -> view(actor, r)).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceView getView(Actor actor, UUID id) {
        authz.require(actor, "invoices", "view");
        IRow r = find(id).filter(x -> x.organizationId().equals(actor.organizationId()))
                .orElseThrow(ApiException::notFound);
        return view(actor, r);
    }

    /** Download do XML: dono do arquivo é a nota; exige `invoices.export`. */
    @Transactional(readOnly = true)
    public void requireXmlAccess(Actor actor, UUID invoiceId) {
        authz.require(actor, "invoices", "export");
        find(invoiceId).filter(x -> x.organizationId().equals(actor.organizationId())).orElseThrow(ApiException::notFound);
    }

    private InvoiceView view(Actor actor, IRow r) {
        List<LineView> lines = lines(r.id());
        List<String> issues = r.state().equals("DRAFT") || r.state().equals("READY") ? issues(r, lines) : List.of();
        FiscalProvider p = provider();
        return new InvoiceView(r.id(), r.orderId(), r.orderNumber(), r.customerName(), r.documentType(), r.environment(),
                r.state(), Decimals.moneyStr(r.total()), r.provider(), r.number(), r.series(), r.accessKey(),
                r.protocol(), r.xmlDocumentId(), r.rejectionCode(), r.rejectionReason(), r.cancelReason(),
                r.cancelProtocol(), r.attempts(), r.reviewedByName(), r.reviewedAt(), r.transmittedAt(),
                r.authorizedAt(), r.createdByName(), r.createdAt(), lines, issues,
                p == null ? "NOT_CONFIGURED" : "CONFIGURED", attempts.forEntity("invoice", r.id()), r.version());
    }

    private List<LineView> lines(UUID id) {
        record R(int lineNo, UUID productId, String description, String ncm, String cfop, String unit,
                 BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
        }
        return jdbc.sql("""
                        SELECT line_no, product_id, description, ncm, cfop, unit, quantity, unit_price, total
                          FROM invoice_lines WHERE invoice_id = :id ORDER BY line_no
                        """).param("id", id).query(R.class).list().stream()
                .map(l -> new LineView(l.lineNo(), l.productId(), l.description(), l.ncm(), l.cfop(), l.unit(),
                        Decimals.str(l.quantity()), Decimals.str(l.unitPrice()), Decimals.moneyStr(l.total())))
                .toList();
    }

    private FiscalProvider.Payload payload(IRow r, Settings s) {
        record C(String name, String document, String address) {
        }
        C c = jdbc.sql("SELECT name, personal_document AS document, address FROM customers WHERE id = :c")
                .param("c", r.customerId()).query(C.class).single();
        record I(int lineNo, String sku, String description, String ncm, String cfop, Integer origin, String unit,
                 BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
        }
        List<FiscalProvider.Item> items = jdbc.sql("""
                        SELECT l.line_no, p.sku, l.description, l.ncm, l.cfop, p.fiscal_origin AS origin, l.unit,
                               l.quantity, l.unit_price, l.total
                          FROM invoice_lines l JOIN products p ON p.id = l.product_id
                         WHERE l.invoice_id = :id ORDER BY l.line_no
                        """).param("id", r.id()).query(I.class).list().stream()
                .map(i -> new FiscalProvider.Item(i.lineNo(), i.sku(), i.description(), i.ncm(), i.cfop(),
                        i.origin() == null ? 0 : i.origin(), i.unit(), i.quantity(), i.unitPrice(), i.total()))
                .toList();
        return new FiscalProvider.Payload(reference(r.id()), r.documentType(), r.environment(), s.series(),
                new FiscalProvider.Issuer(s.issuerDocument(), s.stateRegistration(), s.taxRegime()),
                new FiscalProvider.Recipient(c.name(), c.document(), c.address()), items, r.total());
    }

    /** Referência estável enviada ao provedor: a mesma nota sempre usa a mesma (nunca emissão duplicada). */
    static String reference(UUID invoiceId) {
        return "inv-" + invoiceId;
    }

    private IRow lock(Actor actor, UUID id) {
        jdbc.sql("SELECT id FROM invoices WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return find(id).filter(x -> x.organizationId().equals(actor.organizationId()))
                .orElseThrow(ApiException::notFound);
    }

    private static void checkVersion(IRow r, long expected) {
        if (r.version() != expected) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A nota foi alterada. Recarregue.");
        }
    }

    /** O XML é guardado em nome de quem disparou (pessoa ou identidade técnica de integração). */
    private static Actor actorFor(Actor actor, IRow r) {
        return actor.organizationId().equals(r.organizationId()) ? actor
                : SystemActors.integration(r.organizationId(), r.branchId(), "job:fiscal");
    }

    private static final String SELECT = """
            SELECT i.id, i.organization_id, i.branch_id, i.order_id, o.number AS order_number, c.name AS customer_name,
                   o.customer_id, i.document_type, i.environment, i.state, i.total, i.provider, i.external_ref, i.number,
                   i.series, i.access_key, i.protocol, i.xml_document_id, i.rejection_code, i.rejection_reason,
                   i.cancel_reason, i.cancel_protocol, i.attempts, ru.display_name AS reviewed_by_name, i.reviewed_at,
                   i.transmitted_at, i.authorized_at, cu.display_name AS created_by_name, i.created_at, i.version
              FROM invoices i JOIN orders o ON o.id = i.order_id JOIN customers c ON c.id = o.customer_id
              JOIN app_users cu ON cu.id = i.created_by LEFT JOIN app_users ru ON ru.id = i.reviewed_by
            """;

    private Optional<IRow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id).query(IRow.class).optional();
    }

    private static AuditEntry entry(Actor actor, String action, String type, UUID id, Map<String, Object> changes) {
        boolean service = SystemActors.INTEGRATIONS_USER.equals(actor.userId());
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), service ? "SERVICE" : "USER",
                actor.clientId(), action, type, id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
