package br.com.engenheiro.api.finance.openfinance;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open Finance (pedido do responsável, ADR-021): conexão por conta bancária com consentimento da empresa (CNPJ),
 * importação do extrato uma vez por transação e conciliação com o que já existe no sistema:
 * - saída igual a pagamento de conta/transferência já registrado → concilia (nunca segunda despesa);
 * - saída sem par → despesa SUGERIDA (pendente de aprovação; a aprovação já registra o pagamento pelo banco);
 * - entrada sem par → fica para conferência (nunca vira receita automática).
 * Sem agregador contratado: NÃO CONFIGURADO (503), nada simulado. Conectar/sincronizar/revogar: administrador
 * (consentimento é do representante da empresa); ver: `company_cash.view`; ignorar transação: `expenses.confirm`.
 */
@Service
public class OpenFinanceService {
    static final int MATCH_WINDOW_DAYS = 5;
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final Journal journal;
    private final org.springframework.beans.factory.ObjectProvider<OpenFinanceProvider> providers;
    private final String redirectUri;

    public OpenFinanceService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, Journal journal,
                              org.springframework.beans.factory.ObjectProvider<OpenFinanceProvider> providers,
                              @Value("${engenheiro.open-finance.redirect-uri:}") String redirectUri) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.journal = journal;
        this.providers = providers;
        this.redirectUri = redirectUri;
    }

    public record ConnectCommand(UUID fin_account_id, String institution, String company_document) {
    }

    public record ReasonCommand(String reason) {
    }

    public record ConnectionView(UUID id, UUID fin_account_id, String account_name, String provider, String institution,
                                 String company_document, String state, String consent_url, OffsetDateTime last_sync_at,
                                 String last_error, String provider_balance, String ledger_balance, String created_by_name,
                                 OffsetDateTime created_at) {
    }

    public record Status(String state, String provider, String message, List<ConnectionView> connections) {
    }

    public record TxView(UUID id, UUID fin_account_id, String account_name, LocalDate booked_on, String amount,
                         String direction, String description, String counterpart, String state, String matched_type,
                         UUID matched_id, UUID suggested_expense_id, String suggested_category, String expense_state,
                         String decided_note) {
    }

    public record SyncResult(ConnectionView connection, int imported, int matched, int suggested, int unmatched) {
    }

    // ---------------- estado ----------------
    @Transactional(readOnly = true)
    public Status status(Actor actor) {
        authz.require(actor, "company_cash", "view");
        Optional<OpenFinanceProvider> p = provider();
        List<ConnectionView> cs = jdbc.sql(CONN_SELECT + " WHERE c.organization_id = :o ORDER BY c.created_at DESC")
                .param("o", actor.organizationId()).query(ConnectionView.class).list();
        return p.map(x -> new Status("CONFIGURADO", x.name(), "Agregador " + x.name() + " ativo.", cs))
                .orElse(new Status("NAO_CONFIGURADO", null, "Open Finance NÃO CONFIGURADO: contrate um agregador autorizado "
                        + "e informe as credenciais no servidor. Até lá, nada é importado nem simulado.", cs));
    }

    // ---------------- conectar (consentimento) ----------------
    @Transactional
    public ConnectionView connect(Actor actor, ConnectCommand cmd) {
        authz.requireAdmin(actor, "open_finance.connect");
        List<FieldError> errors = new ArrayList<>();
        record A(UUID id, String kind) {
        }
        A acc = cmd == null || cmd.fin_account_id() == null ? null : jdbc.sql(
                        "SELECT id, kind FROM fin_accounts WHERE id = :id AND organization_id = :o AND status = 'ACTIVE'")
                .param("id", cmd.fin_account_id()).param("o", actor.organizationId()).query(A.class).optional().orElse(null);
        if (acc == null || !acc.kind().equals("BANK")) {
            errors.add(new FieldError("fin_account_id", "Escolha uma conta bancária ativa da empresa"));
        }
        String inst = Text.trim(cmd == null ? null : cmd.institution());
        if (inst == null || inst.length() > 120) {
            errors.add(new FieldError("institution", "Informe o banco"));
        }
        String cnpj = cmd == null || cmd.company_document() == null ? null : cmd.company_document().replaceAll("\\D", "");
        if (!validCnpj(cnpj)) {
            errors.add(new FieldError("company_document", "CNPJ inválido"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        OpenFinanceProvider p = requireProvider();
        OpenFinanceProvider.Consent consent = p.startConsent(cnpj, inst, redirectUri);
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO bank_connections (id, organization_id, fin_account_id, provider, institution, company_document,
                                                          external_id, state, consent_url, created_by)
                            VALUES (:id, :o, :a, :p, :i, :c, :x, 'PENDING_CONSENT', :u, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("a", acc.id()).param("p", p.name())
                    .param("i", inst).param("c", cnpj).param("x", consent.externalId()).param("u", consent.consentUrl())
                    .param("by", actor.userId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Esta conta já tem conexão ativa ou pendente.");
        }
        audit.record(entry(actor, "open_finance.connect", id, Map.of("institution", inst, "provider", p.name())));
        return connection(id);
    }

    @Transactional
    public ConnectionView revoke(Actor actor, UUID id, ReasonCommand cmd) {
        authz.requireAdmin(actor, "open_finance.revoke");
        String reason = Text.trim(cmd == null ? null : cmd.reason());
        record C(String externalId, String state) {
        }
        C c = jdbc.sql("SELECT external_id, state FROM bank_connections WHERE id = :id AND organization_id = :o FOR UPDATE")
                .param("id", id).param("o", actor.organizationId()).query(C.class).optional().orElseThrow(ApiException::notFound);
        if (c.state().equals("REVOKED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Conexão já revogada.");
        }
        provider().ifPresent(p -> {
            if (c.externalId() != null) {
                p.revoke(c.externalId());
            }
        });
        jdbc.sql("UPDATE bank_connections SET state = 'REVOKED', last_error = :r, version = version + 1 WHERE id = :id")
                .param("r", reason).param("id", id).update();
        audit.record(entry(actor, "open_finance.revoke", id, Map.of("reason", reason == null ? "" : reason)));
        return connection(id);
    }

    // ---------------- sincronizar e conciliar ----------------
    @Transactional
    public SyncResult sync(Actor actor, UUID id) {
        authz.requireAdmin(actor, "open_finance.sync");
        OpenFinanceProvider p = requireProvider();
        record C(UUID id, UUID finAccountId, String externalId, OffsetDateTime lastSyncAt, boolean isDefault) {
        }
        C c = jdbc.sql("""
                        SELECT c.id, c.fin_account_id, c.external_id, c.last_sync_at, a.is_default FROM bank_connections c
                          JOIN fin_accounts a ON a.id = c.fin_account_id
                         WHERE c.id = :id AND c.organization_id = :o AND c.state IN ('PENDING_CONSENT', 'ACTIVE') FOR UPDATE OF c
                        """).param("id", id).param("o", actor.organizationId()).query(C.class).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conexão não encontrada ou revogada."));
        LocalDate since = c.lastSyncAt() == null ? LocalDate.now(Journal.COMMERCIAL).minusDays(90)
                : c.lastSyncAt().atZoneSameInstant(Journal.COMMERCIAL).toLocalDate().minusDays(7);
        OpenFinanceProvider.Snapshot snap;
        try {
            snap = p.fetch(c.externalId(), since);
        } catch (RuntimeException e) {
            jdbc.sql("UPDATE bank_connections SET last_error = :e WHERE id = :id").param("e", "Falha no agregador")
                    .param("id", c.id()).update();
            throw new ApiException(ErrorCode.INTEGRATION_UNCERTAIN, "O agregador não respondeu; nada foi importado. Tente de novo.");
        }
        jdbc.sql("""
                        UPDATE bank_connections SET state = :s, provider_balance = :b, provider_balance_at = now(),
                               last_sync_at = now(), last_error = NULL, version = version + 1 WHERE id = :id
                        """).param("s", snap.state()).param("b", snap.balance()).param("id", c.id()).update();
        int imported = 0;
        int matched = 0;
        int suggested = 0;
        int unmatched = 0;
        if (snap.state().equals("ACTIVE")) {
            for (OpenFinanceProvider.Tx t : snap.transactions()) {
                if (t.amount() == null || t.amount().signum() <= 0 || !List.of("IN", "OUT").contains(t.direction())) {
                    continue; // ignora registro inválido do agregador (não inventa valor)
                }
                UUID txId = Ids.newId();
                int ins = jdbc.sql("""
                                INSERT INTO bank_transactions (id, organization_id, connection_id, fin_account_id, provider_tx_id,
                                                               booked_on, amount, direction, description, counterpart, state)
                                VALUES (:id, :o, :c, :a, :p, :d, :v, :dir, :desc, :cp, 'UNMATCHED')
                                ON CONFLICT (fin_account_id, provider_tx_id) DO NOTHING
                                """).param("id", txId).param("o", actor.organizationId()).param("c", c.id())
                        .param("a", c.finAccountId()).param("p", t.id()).param("d", t.bookedOn())
                        .param("v", Decimals.money(t.amount())).param("dir", t.direction())
                        .param("desc", truncate(t.description() == null ? "(sem descrição)" : t.description(), 300))
                        .param("cp", t.counterpart() == null ? null : truncate(t.counterpart(), 120)).update();
                if (ins == 0) {
                    continue; // já importada antes: nada muda
                }
                imported++;
                String result = classify(actor, txId, c.finAccountId(), c.isDefault(), t);
                switch (result) {
                    case "MATCHED" -> matched++;
                    case "SUGGESTED" -> suggested++;
                    default -> unmatched++;
                }
            }
        }
        audit.record(entry(actor, "open_finance.sync", c.id(), Map.of("imported", imported, "matched", matched,
                "suggested", suggested, "unmatched", unmatched)));
        return new SyncResult(connection(c.id()), imported, matched, suggested, unmatched);
    }

    /** Concilia com o que já existe; saída sem par vira despesa sugerida; entrada sem par fica para conferência. */
    private String classify(Actor actor, UUID txId, UUID account, boolean isDefault, OpenFinanceProvider.Tx t) {
        BigDecimal amount = Decimals.money(t.amount());
        LocalDate lo = t.bookedOn().minusDays(MATCH_WINDOW_DAYS);
        LocalDate hi = t.bookedOn().plusDays(MATCH_WINDOW_DAYS);
        List<String[]> candidates = new ArrayList<>();
        if (t.direction().equals("OUT")) {
            candidates.add(new String[] {"payable_settlement", """
                    SELECT s.id FROM payable_settlements s WHERE s.kind = 'PAYMENT' AND s.state = 'CONFIRMED'
                       AND s.fin_account_id = :a AND s.amount = :v AND s.paid_on BETWEEN :lo AND :hi
                    """});
            candidates.add(new String[] {"fin_transfer", """
                    SELECT t.id FROM fin_transfers t WHERE t.state = 'CONFIRMED' AND t.from_account_id = :a AND t.amount = :v
                       AND t.transfer_date BETWEEN :lo AND :hi
                    """});
        } else {
            candidates.add(new String[] {"receivable_settlement", """
                    SELECT s.id FROM receivable_settlements s WHERE s.state = 'CONFIRMED' AND s.fin_account_id = :a
                       AND s.received = :v AND s.received_on BETWEEN :lo AND :hi
                    """});
            candidates.add(new String[] {"fin_transfer", """
                    SELECT t.id FROM fin_transfers t WHERE t.state = 'CONFIRMED' AND t.to_account_id = :a AND t.amount = :v
                       AND t.transfer_date BETWEEN :lo AND :hi
                    """});
            if (isDefault) { // Pix/transferência de cliente entram na conta bancária padrão
                candidates.add(new String[] {"payment", """
                        SELECT p.id FROM payments p WHERE p.method IN ('PIX', 'BANK_TRANSFER') AND p.state = 'CONFIRMED'
                           AND p.amount = :v AND (p.confirmed_at AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN :lo AND :hi
                           AND :a = :a
                        """});
            }
        }
        for (String[] c : candidates) {
            List<UUID> ids = jdbc.sql(c[1] + " AND NOT EXISTS (SELECT 1 FROM bank_transactions b WHERE b.matched_type = '" + c[0]
                            + "' AND b.matched_id = " + (c[0].equals("payment") ? "p" : c[0].equals("fin_transfer") ? "t" : "s") + ".id)"
                            + " ORDER BY 1 LIMIT 1")
                    .param("a", account).param("v", amount).param("lo", lo).param("hi", hi).query(UUID.class).list();
            if (!ids.isEmpty()) {
                jdbc.sql("UPDATE bank_transactions SET state = 'MATCHED', matched_type = :t, matched_id = :m WHERE id = :id")
                        .param("t", c[0]).param("m", ids.get(0)).param("id", txId).update();
                return "MATCHED";
            }
        }
        if (t.direction().equals("IN")) {
            return "UNMATCHED";
        }
        try {
            journal.requireOpen(actor.organizationId(), t.bookedOn());
        } catch (ApiException closed) {
            jdbc.sql("UPDATE bank_transactions SET decided_note = 'Período fechado: conferir manualmente' WHERE id = :id")
                    .param("id", txId).update();
            return "UNMATCHED";
        }
        String category = guessCategory(t.description() + " " + (t.counterpart() == null ? "" : t.counterpart()));
        UUID expense = Ids.newId();
        String desc = truncate(t.description() == null ? "Débito no banco" : t.description(), 300);
        jdbc.sql("""
                        INSERT INTO expenses (id, organization_id, branch_id, category, subcategory, description, payee, amount,
                                              competence, due_date, created_by, bank_transaction_id)
                        VALUES (:id, :o, :b, :c, 'Importado do banco', :d, :p, :a, :comp, :comp, :by, :tx)
                        """).param("id", expense).param("o", actor.organizationId()).param("b", actor.branchId())
                .param("c", category).param("d", desc).param("p", t.counterpart() == null ? null : truncate(t.counterpart(), 120))
                .param("a", amount).param("comp", t.bookedOn()).param("by", actor.userId()).param("tx", txId).update();
        jdbc.sql("UPDATE bank_transactions SET state = 'SUGGESTED', suggested_expense_id = :e, suggested_category = :c WHERE id = :id")
                .param("e", expense).param("c", category).param("id", txId).update();
        return "SUGGESTED";
    }

    /** Categoria provável pelo texto do extrato (sugestão; quem aprova pode corrigir recusando e cadastrando). */
    static String guessCategory(String text) {
        String s = text.toUpperCase(Locale.ROOT);
        if (s.matches(".*(DARF|DAS |SIMPLES|IMPOSTO|INSS|FGTS|ICMS|ISS|TRIBUT|GPS).*")) {
            return "IMPOSTOS_TAXAS";
        }
        if (s.matches(".*(SALARIO|SALÁRIO|FOLHA|PROLABORE|PRÓ-LABORE|FERIAS|FÉRIAS|VALE TRANSP).*")) {
            return "FUNCIONARIOS";
        }
        if (s.matches(".*(POSTO|COMBUST|GASOLINA|DIESEL|OFICINA|PNEU|PEDAGIO|PEDÁGIO|IPVA|SEGURO AUTO|DETRAN).*")) {
            return "VEICULOS";
        }
        if (s.matches(".*(ALUGUEL|ENERGIA|LUZ|AGUA|ÁGUA|SANEAMENTO|INTERNET|TELEFON|CONDOMIN|MANUTENC).*")) {
            return "ESTRUTURA";
        }
        return "OUTRAS";
    }

    // ---------------- consulta e conferência ----------------
    @Transactional(readOnly = true)
    public List<TxView> transactions(Actor actor, String state) {
        authz.require(actor, "company_cash", "view");
        if (state != null && !List.of("MATCHED", "SUGGESTED", "UNMATCHED", "IGNORED").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        return jdbc.sql("""
                        SELECT b.id, b.fin_account_id, a.name AS account_name, b.booked_on, CAST(b.amount AS text) AS amount,
                               b.direction, b.description, b.counterpart, b.state, b.matched_type, b.matched_id,
                               b.suggested_expense_id, b.suggested_category, x.state AS expense_state, b.decided_note
                          FROM bank_transactions b JOIN fin_accounts a ON a.id = b.fin_account_id
                          LEFT JOIN expenses x ON x.id = b.suggested_expense_id
                         WHERE b.organization_id = :o AND (CAST(:st AS text) IS NULL OR b.state = CAST(:st AS text))
                         ORDER BY b.booked_on DESC, b.imported_at DESC LIMIT 300
                        """).param("o", actor.organizationId()).param("st", state).query(TxView.class).list();
    }

    @Transactional
    public TxView ignore(Actor actor, UUID id, ReasonCommand cmd) {
        authz.require(actor, "expenses", "confirm");
        String reason = Text.trim(cmd == null ? null : cmd.reason());
        if (reason == null || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("reason", "Obrigatório")));
        }
        String state = jdbc.sql("SELECT state FROM bank_transactions WHERE id = :id AND organization_id = :o FOR UPDATE")
                .param("id", id).param("o", actor.organizationId()).query(String.class).optional().orElseThrow(ApiException::notFound);
        if (!state.equals("UNMATCHED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só transação sem par pode ser ignorada (sugerida: recuse a despesa).");
        }
        jdbc.sql("UPDATE bank_transactions SET state = 'IGNORED', decided_by = :by, decided_note = :n, version = version + 1 WHERE id = :id")
                .param("by", actor.userId()).param("n", reason).param("id", id).update();
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                "open_finance.ignore", "bank_transaction", id.toString(), "SUCCESS", null, Map.of("reason", reason), "api"));
        return transactions(actor, null).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------------- apoio ----------------
    Optional<OpenFinanceProvider> provider() {
        return providers.orderedStream().filter(OpenFinanceProvider::configured).findFirst();
    }

    private OpenFinanceProvider requireProvider() {
        return provider().orElseThrow(() -> new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE,
                "Open Finance NÃO CONFIGURADO: contrate um agregador autorizado e informe as credenciais no servidor. "
                        + "Nada foi conectado nem importado."));
    }

    private ConnectionView connection(UUID id) {
        return jdbc.sql(CONN_SELECT + " WHERE c.id = :id").param("id", id).query(ConnectionView.class).single();
    }

    static final String CONN_SELECT = """
            SELECT c.id, c.fin_account_id, a.name AS account_name, c.provider, c.institution, c.company_document, c.state,
                   c.consent_url, c.last_sync_at, c.last_error, CAST(c.provider_balance AS text) AS provider_balance,
                   CAST((SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                          WHERE e.organization_id = c.organization_id AND l.account = a.ledger_account
                            AND (l.fin_account_id = a.id OR (l.fin_account_id IS NULL AND a.is_default))) AS text) AS ledger_balance,
                   u.display_name AS created_by_name, c.created_at
              FROM bank_connections c JOIN fin_accounts a ON a.id = c.fin_account_id JOIN app_users u ON u.id = c.created_by
            """;

    static boolean validCnpj(String c) {
        if (c == null || !c.matches("\\d{14}") || c.chars().distinct().count() == 1) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int s = 0;
        for (int i = 0; i < 12; i++) {
            s += (c.charAt(i) - '0') * w1[i];
        }
        int d1 = s % 11 < 2 ? 0 : 11 - s % 11;
        s = 0;
        for (int i = 0; i < 13; i++) {
            s += (i == 12 ? d1 : c.charAt(i) - '0') * w2[i];
        }
        int d2 = s % 11 < 2 ? 0 : 11 - s % 11;
        return d1 == c.charAt(12) - '0' && d2 == c.charAt(13) - '0';
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                "bank_connection", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
