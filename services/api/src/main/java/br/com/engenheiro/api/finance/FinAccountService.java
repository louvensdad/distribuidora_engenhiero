package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.finance.Journal.Line;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contas financeiras (FIN-02, FIN-05): saldo = soma do livro na conta (inicial lançado como SALDOS_INICIAIS);
 * extrato com saldo anterior, movimentos e saldo final; transferência interna (não muda o consolidado); estorno.
 * Ver saldos e extratos: `company_cash.view`. Criar conta e transferir: administrador (sem ação no contrato — ADR-020).
 */
@Service
public class FinAccountService {
    public static final String DRAWERS = "CAIXAS_TURNO";
    public static final String BANK = "BANCO_PRINCIPAL";
    public static final String ACQUIRER = "ADQUIRENTE_CARTOES";
    public static final String SAFE = "COFRE_EMPRESA";

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final Journal journal;

    public FinAccountService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                             Journal journal) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.journal = journal;
    }

    public record CreateCommand(String name, String kind, String opening_balance) {
    }

    public record TransferCommand(UUID from_account_id, UUID to_account_id, String amount, String note) {
    }

    public record ReasonCommand(String reason) {
    }

    public record AccountView(UUID id, String code, String name, String kind, String ledger_account, boolean is_default,
                              String status, String balance, long version) {
    }

    public record StatementLine(UUID entry_id, LocalDate entry_date, LocalDate competence, String event_kind, String memo,
                                String source_type, UUID source_id, String debit, String credit, String balance,
                                String created_by_name, boolean reversal) {
    }

    public record Statement(AccountView account, LocalDate from, LocalDate to, String opening_balance, String inflows,
                            String outflows, String closing_balance, List<StatementLine> lines) {
    }

    public record TransferView(UUID id, UUID from_account_id, String from_name, UUID to_account_id, String to_name,
                               String amount, LocalDate transfer_date, String note, String state, String created_by_name,
                               OffsetDateTime created_at, String reverse_reason) {
    }

    record Acc(UUID id, UUID organizationId, String code, String name, String kind, String ledgerAccount,
               boolean isDefault, String status, long version) {
    }

    // ---------------- contas padrão ----------------
    /** Garante as contas padrão da organização (idempotente). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void ensureDefaults(UUID org) {
        Object[][] defs = {
                {DRAWERS, "Caixas de turno (balcão e motoristas)", "CAIXA", "CASH_DRAWERS", true},
                {BANK, "Banco principal", "BANCO", "BANK", true},
                {ACQUIRER, "Adquirente de cartões (a receber)", "RECEBIVEL_CARTAO", "CARD_ACQUIRER", true},
                {SAFE, "Caixa da empresa (cofre)", "CAIXA", "COMPANY_CASH", false}};
        for (Object[] d : defs) {
            jdbc.sql("""
                            INSERT INTO fin_accounts (id, organization_id, code, name, ledger_account, kind, is_default)
                            VALUES (:id, :o, :c, :n, :l, :k, :def) ON CONFLICT (organization_id, code) DO NOTHING
                            """).param("id", Ids.newId()).param("o", org).param("c", d[0]).param("n", d[1])
                    .param("l", d[2]).param("k", d[3]).param("def", d[4]).update();
        }
    }

    UUID idOf(UUID org, String code) {
        ensureDefaults(org);
        return jdbc.sql("SELECT id FROM fin_accounts WHERE organization_id = :o AND code = :c").param("o", org)
                .param("c", code).query(UUID.class).single();
    }

    // ---------------- consultas ----------------
    /**
     * Com `company_cash.view`: contas e saldos. Só com `expenses.confirm`/`payments.confirm` (quem paga ou recebe):
     * contas ativas de caixa/banco para escolher, com saldo oculto (consolidado bloqueado por padrão).
     */
    @Transactional
    public List<AccountView> list(Actor actor) {
        ensureDefaults(actor.organizationId());
        if (authz.decide(actor, "company_cash", "view").allowed()) {
            return accounts(actor.organizationId()).stream().map(a -> view(a, balance(a, null))).toList();
        }
        if (!authz.decide(actor, "expenses", "confirm").allowed() && !authz.decide(actor, "payments", "confirm").allowed()) {
            authz.require(actor, "company_cash", "view");
        }
        return accounts(actor.organizationId()).stream()
                .filter(a -> a.status().equals("ACTIVE") && !a.kind().equals("CASH_DRAWERS")
                        && !a.ledgerAccount().equals("RECEBIVEL_CARTAO"))
                .map(a -> new AccountView(a.id(), a.code(), a.name(), a.kind(), a.ledgerAccount(), a.isDefault(), a.status(),
                        null, a.version())).toList();
    }

    @Transactional
    public Statement statement(Actor actor, UUID id, LocalDate from, LocalDate to) {
        authz.require(actor, "company_cash", "view");
        ensureDefaults(actor.organizationId());
        Acc a = find(actor.organizationId(), id).orElseThrow(ApiException::notFound);
        LocalDate f = from == null ? LocalDate.now(Journal.COMMERCIAL).withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1) : to;
        if (!t.isAfter(f) || f.plusYears(2).isBefore(t)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Intervalo inválido (fim exclusivo, até 2 anos).");
        }
        BigDecimal opening = balance(a, f);
        record Raw(UUID entryId, LocalDate entryDate, LocalDate competence, String eventKind, String memo, String sourceType,
                   UUID sourceId, BigDecimal debit, BigDecimal credit, String createdByName, UUID reversalOf) {
        }
        List<Raw> raw = jdbc.sql("""
                        SELECT e.id AS entry_id, e.entry_date, e.competence, e.event_kind, e.memo, e.source_type, e.source_id,
                               sum(l.debit) AS debit, sum(l.credit) AS credit, u.display_name AS created_by_name, e.reversal_of
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                          JOIN app_users u ON u.id = e.created_by
                         WHERE e.organization_id = :o AND l.account = :ledger
                           AND (l.fin_account_id = :id OR (l.fin_account_id IS NULL AND :def))
                           AND e.entry_date >= :f AND e.entry_date < :t
                         GROUP BY e.id, u.display_name ORDER BY e.entry_date, e.created_at, e.id
                        """).param("o", a.organizationId()).param("ledger", a.ledgerAccount()).param("id", a.id())
                .param("def", a.isDefault()).param("f", f).param("t", t).query(Raw.class).list();
        BigDecimal running = opening;
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        List<StatementLine> lines = new ArrayList<>();
        for (Raw r : raw) {
            running = running.add(r.debit()).subtract(r.credit());
            in = in.add(r.debit());
            out = out.add(r.credit());
            lines.add(new StatementLine(r.entryId(), r.entryDate(), r.competence(), r.eventKind(), r.memo(), r.sourceType(),
                    r.sourceId(), Decimals.moneyStr(r.debit()), Decimals.moneyStr(r.credit()), Decimals.moneyStr(running),
                    r.createdByName(), r.reversalOf() != null));
        }
        return new Statement(view(a, balance(a, null)), f, t, Decimals.moneyStr(opening), Decimals.moneyStr(in),
                Decimals.moneyStr(out), Decimals.moneyStr(running), lines);
    }

    // ---------------- cadastro ----------------
    @Transactional
    public AccountView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.requireAdmin(actor, "company_cash.create_account");
        ensureDefaults(actor.organizationId());
        return idem.execute(actor, "finance.accounts.create", idemKey, cmd, AccountView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            String name = Text.trim(cmd.name());
            if (name == null || name.length() > 80) {
                errors.add(new FieldError("name", "Informe o nome (até 80 caracteres)"));
            }
            String kind = cmd.kind();
            if (!"BANK".equals(kind) && !"COMPANY_CASH".equals(kind)) {
                errors.add(new FieldError("kind", "Use BANK (banco) ou COMPANY_CASH (caixa da empresa)"));
            }
            BigDecimal opening = Decimals.parseOptional(cmd.opening_balance(), Decimals.MONEY_SCALE, "opening_balance", errors);
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            UUID id = Ids.newId();
            String code = (kind.equals("BANK") ? "BANCO_" : "CAIXA_") + id.toString().substring(24).toUpperCase();
            jdbc.sql("""
                            INSERT INTO fin_accounts (id, organization_id, code, name, ledger_account, kind, created_by)
                            VALUES (:id, :o, :c, :n, :l, :k, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("c", code).param("n", name)
                    .param("l", kind.equals("BANK") ? "BANCO" : "CAIXA").param("k", kind).param("by", actor.userId()).update();
            if (opening != null && opening.signum() > 0) {
                String ledger = kind.equals("BANK") ? "BANCO" : "CAIXA";
                journal.post(actor, actor.branchId(), "fin_account", id, "OPENING_BALANCE", "Saldo inicial — " + name, null,
                        List.of(Line.debit(ledger, opening).at(id), Line.credit("SALDOS_INICIAIS", opening)));
            }
            audit.record(entry(actor, "finance.accounts.create", "fin_account", id, Map.of("name", name, "kind", kind,
                    "opening_balance", opening == null ? "0.00" : opening.toPlainString())));
            Acc a = find(actor.organizationId(), id).orElseThrow();
            return view(a, balance(a, null));
        });
    }

    // ---------------- transferências ----------------
    @Transactional
    public TransferView transfer(Actor actor, String idemKey, TransferCommand cmd) {
        authz.requireAdmin(actor, "company_cash.transfer");
        ensureDefaults(actor.organizationId());
        return idem.execute(actor, "finance.transfers.create", idemKey, cmd, TransferView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            Acc from = cmd.from_account_id() == null ? null : lock(actor.organizationId(), cmd.from_account_id());
            Acc to = cmd.to_account_id() == null ? null : lock(actor.organizationId(), cmd.to_account_id());
            if (from == null || !from.status().equals("ACTIVE") || from.ledgerAccount().equals("RECEBIVEL_CARTAO")) {
                errors.add(new FieldError("from_account_id", "Conta de origem inválida (caixa ou banco ativo)"));
            }
            if (to == null || !to.status().equals("ACTIVE") || to.ledgerAccount().equals("RECEBIVEL_CARTAO")) {
                errors.add(new FieldError("to_account_id", "Conta de destino inválida (caixa ou banco ativo)"));
            }
            if (from != null && to != null && from.id().equals(to.id())) {
                errors.add(new FieldError("to_account_id", "Origem e destino iguais"));
            }
            BigDecimal amount = Decimals.parse(cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
            String note = Text.trim(cmd.note());
            if (note != null && note.length() > 500) {
                errors.add(new FieldError("note", "Até 500 caracteres"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            requireFunds(from, amount, "amount");
            UUID id = Ids.newId();
            LocalDate today = LocalDate.now(Journal.COMMERCIAL);
            jdbc.sql("""
                            INSERT INTO fin_transfers (id, organization_id, from_account_id, to_account_id, amount,
                                                       transfer_date, note, created_by)
                            VALUES (:id, :o, :f, :t, :a, :d, :n, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("f", from.id()).param("t", to.id())
                    .param("a", amount).param("d", today).param("n", note).param("by", actor.userId()).update();
            journal.post(actor, actor.branchId(), "fin_transfer", id, "TRANSFER", "Transferência " + from.name() + " → "
                            + to.name(), null,
                    List.of(Line.debit(to.ledgerAccount(), amount).at(to.id()), Line.credit(from.ledgerAccount(), amount).at(from.id())));
            audit.record(entry(actor, "finance.transfers.create", "fin_transfer", id, Map.of("from", from.code(), "to", to.code(),
                    "amount", amount.toPlainString())));
            return transferView(id);
        });
    }

    @Transactional
    public TransferView reverseTransfer(Actor actor, UUID id, ReasonCommand cmd) {
        authz.requireAdmin(actor, "company_cash.transfer");
        String reason = requireReason(cmd == null ? null : cmd.reason());
        String state = jdbc.sql("SELECT state FROM fin_transfers WHERE id = :id AND organization_id = :o FOR UPDATE")
                .param("id", id).param("o", actor.organizationId()).query(String.class).optional()
                .orElseThrow(ApiException::notFound);
        if (!state.equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Transferência já estornada.");
        }
        UUID to = jdbc.sql("SELECT to_account_id FROM fin_transfers WHERE id = :id").param("id", id).query(UUID.class).single();
        BigDecimal amount = jdbc.sql("SELECT amount FROM fin_transfers WHERE id = :id").param("id", id)
                .query(BigDecimal.class).single();
        requireFunds(lock(actor.organizationId(), to), amount, "amount");
        jdbc.sql("""
                        UPDATE fin_transfers SET state = 'REVERSED', reversed_by = :by, reversed_at = now(), reverse_reason = :r
                         WHERE id = :id
                        """).param("by", actor.userId()).param("r", reason).param("id", id).update();
        journal.reverse(actor, "fin_transfer", id, "TRANSFER", "TRANSFER_REVERSED", reason);
        audit.record(entry(actor, "finance.transfers.reverse", "fin_transfer", id, Map.of("reason", reason)));
        return transferView(id);
    }

    @Transactional(readOnly = true)
    public List<TransferView> transfers(Actor actor) {
        authz.require(actor, "company_cash", "view");
        return jdbc.sql(TRANSFER_SELECT + " WHERE t.organization_id = :o ORDER BY t.created_at DESC LIMIT 100")
                .param("o", actor.organizationId()).query(TransferView.class).list();
    }

    // ---------------- apoio (também usado por pagamentos/recebimentos) ----------------
    /** Conta ativa de caixa ou banco para pagar/receber (bloqueada). */
    Acc moneyAccount(Actor actor, UUID id, String field, List<FieldError> errors) {
        ensureDefaults(actor.organizationId());
        Acc a = id == null ? null : lock(actor.organizationId(), id);
        if (a == null || !a.status().equals("ACTIVE") || a.ledgerAccount().equals("RECEBIVEL_CARTAO")) {
            errors.add(new FieldError(field, "Escolha um caixa ou banco ativo"));
            return null;
        }
        if (a.kind().equals("CASH_DRAWERS")) {
            // Dinheiro dos caixas de turno só se move pelo caixa do operador (ou recolhimento por transferência).
            errors.add(new FieldError(field, "Use o caixa da empresa ou um banco (caixas de turno: faça o recolhimento antes)"));
            return null;
        }
        return a;
    }

    /** Caixa não fica negativo (banco pode, ex.: cheque especial — registrado como está). */
    void requireFunds(Acc a, BigDecimal amount, String field) {
        if (a.ledgerAccount().equals("CAIXA") && balance(a, null).compareTo(amount) < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Saldo insuficiente em " + a.name() + " ("
                    + Decimals.moneyStr(balance(a, null)) + ").",
                    List.of(new FieldError(field, "Maior que o saldo do caixa")));
        }
    }

    /** Saldo da conta até a data (exclusiva) ou total, pela data do lançamento (visão de caixa). */
    BigDecimal balance(Acc a, LocalDate before) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND l.account = :ledger
                           AND (l.fin_account_id = :id OR (l.fin_account_id IS NULL AND :def))
                           AND (CAST(:b AS date) IS NULL OR e.entry_date < CAST(:b AS date))
                        """).param("o", a.organizationId()).param("ledger", a.ledgerAccount()).param("id", a.id())
                .param("def", a.isDefault()).param("b", before).query(BigDecimal.class).single();
    }

    List<Acc> accounts(UUID org) {
        return jdbc.sql(ACC_SELECT + " WHERE organization_id = :o ORDER BY is_default DESC, kind, name").param("o", org)
                .query(Acc.class).list();
    }

    Optional<Acc> find(UUID org, UUID id) {
        return jdbc.sql(ACC_SELECT + " WHERE organization_id = :o AND id = :id").param("o", org).param("id", id)
                .query(Acc.class).optional();
    }

    Acc lock(UUID org, UUID id) {
        return jdbc.sql(ACC_SELECT + " WHERE organization_id = :o AND id = :id FOR UPDATE").param("o", org).param("id", id)
                .query(Acc.class).optional().orElse(null);
    }

    AccountView view(Acc a, BigDecimal balance) {
        return new AccountView(a.id(), a.code(), a.name(), a.kind(), a.ledgerAccount(), a.isDefault(), a.status(),
                Decimals.moneyStr(balance), a.version());
    }

    private TransferView transferView(UUID id) {
        return jdbc.sql(TRANSFER_SELECT + " WHERE t.id = :id").param("id", id).query(TransferView.class).single();
    }

    static String requireReason(String r) {
        String t = Text.trim(r);
        if (t == null || t.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório (até 500 caracteres)")));
        }
        return t;
    }

    static AuditEntry entry(Actor actor, String action, String type, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                type, id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }

    static final String ACC_SELECT = """
            SELECT id, organization_id, code, name, kind, ledger_account, is_default, status, version FROM fin_accounts
            """;
    static final String TRANSFER_SELECT = """
            SELECT t.id, t.from_account_id, fa.name AS from_name, t.to_account_id, ta.name AS to_name,
                   CAST(t.amount AS text) AS amount, t.transfer_date, t.note, t.state, u.display_name AS created_by_name,
                   t.created_at, t.reverse_reason
              FROM fin_transfers t JOIN fin_accounts fa ON fa.id = t.from_account_id
              JOIN fin_accounts ta ON ta.id = t.to_account_id JOIN app_users u ON u.id = t.created_by
            """;
}
