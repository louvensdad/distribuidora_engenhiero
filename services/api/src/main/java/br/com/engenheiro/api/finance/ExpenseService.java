package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.documents.DocumentService;
import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.finance.Journal.Line;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Cursor;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Despesas por categoria (FIN-01): competência, vencimento, situação e comprovante. Reconhecida uma vez na aprovação
 * (despesa da categoria contra FORNECEDORES + conta a pagar); paga pela conta a pagar (sem nova despesa); corrigida
 * por estorno vinculado. Retirada do sócio fica fora do resultado (RETIRADAS_SOCIOS).
 * Capacidades: `expenses.create`, `expenses.approve` (com alçada; quem cadastrou não aprova, exceto administrador),
 * `expenses.reverse`, `expenses.view`.
 */
@Service
public class ExpenseService {
    public static final Map<String, String> LEDGER = Map.of(
            "FUNCIONARIOS", "DESPESAS_FUNCIONARIOS", "VEICULOS", "DESPESAS_VEICULOS", "ESTRUTURA", "DESPESAS_ESTRUTURA",
            "IMPOSTOS_TAXAS", "DESPESAS_IMPOSTOS", "OUTRAS", "DESPESAS_OUTRAS", "RETIRADA_SOCIO", "RETIRADAS_SOCIOS");
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("x.organization_id", "x.branch_id", null, "x.created_by", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final Journal journal;
    private final PayableService payables;
    private final DocumentService documents;

    public ExpenseService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                          Journal journal, PayableService payables, DocumentService documents) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.journal = journal;
        this.payables = payables;
        this.documents = documents;
    }

    public record CreateCommand(String category, String subcategory, String description, String payee, UUID supplier_id,
                                String amount, LocalDate competence, LocalDate due_date) {
    }

    public record NoteCommand(String note) {
    }

    public record ExpenseView(UUID id, String category, String subcategory, String description, String payee,
                              UUID supplier_id, String supplier_name, String amount, LocalDate competence, LocalDate due_date,
                              String state, UUID payable_id, String payable_state, String payable_balance, String branch,
                              String created_by_name, OffsetDateTime created_at, String decided_by_name,
                              OffsetDateTime decided_at, String decided_note, String reverse_reason, long version) {
    }

    public record Page(List<ExpenseView> items, String next_cursor) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String category, String subcategory, String description,
               String payee, UUID supplierId, String supplierName, BigDecimal amount, LocalDate competence,
               LocalDate dueDate, String state, UUID payableId, String payableState, BigDecimal payableBalance,
               String branch, UUID createdBy, String createdByName, OffsetDateTime createdAt, String decidedByName,
               OffsetDateTime decidedAt, String decidedNote, String reverseReason, long version) {
    }

    static final String SELECT = """
            SELECT x.id, x.organization_id, x.branch_id, x.category, x.subcategory, x.description, x.payee, x.supplier_id,
                   s.name AS supplier_name, x.amount, x.competence, x.due_date, x.state, x.payable_id,
                   p.state AS payable_state, p.balance AS payable_balance, b.name AS branch, x.created_by,
                   u.display_name AS created_by_name, x.created_at, d.display_name AS decided_by_name, x.decided_at,
                   x.decided_note, x.reverse_reason, x.version
              FROM expenses x JOIN branches b ON b.id = x.branch_id JOIN app_users u ON u.id = x.created_by
              LEFT JOIN app_users d ON d.id = x.decided_by LEFT JOIN suppliers s ON s.id = x.supplier_id
              LEFT JOIN payables p ON p.id = x.payable_id
            """;

    // ---------------- cadastrar ----------------
    @Transactional
    public ExpenseView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, "expenses", "create");
        return idem.execute(actor, "expenses.create", idemKey, cmd, ExpenseView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            if (cmd.category() == null || !LEDGER.containsKey(cmd.category())) {
                errors.add(new FieldError("category", "Escolha a categoria"));
            }
            String sub = Text.trim(cmd.subcategory());
            if (sub == null || sub.length() > 60) {
                errors.add(new FieldError("subcategory", "Informe a subcategoria (até 60 caracteres)"));
            }
            String desc = Text.trim(cmd.description());
            if (desc == null || desc.length() > 300) {
                errors.add(new FieldError("description", "Descreva a despesa (até 300 caracteres)"));
            }
            String payee = Text.trim(cmd.payee());
            if (payee != null && payee.length() > 120) {
                errors.add(new FieldError("payee", "Até 120 caracteres"));
            }
            if (cmd.supplier_id() != null && jdbc.sql("SELECT count(*) FROM suppliers WHERE id = :s AND organization_id = :o")
                    .param("s", cmd.supplier_id()).param("o", actor.organizationId()).query(Integer.class).single() == 0) {
                errors.add(new FieldError("supplier_id", "Fornecedor não encontrado"));
            }
            BigDecimal amount = Decimals.parse(cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
            LocalDate today = LocalDate.now(Journal.COMMERCIAL);
            LocalDate competence = cmd.competence() == null ? today : cmd.competence();
            if (competence.isAfter(today.plusMonths(1)) || competence.isBefore(today.minusYears(2))) {
                errors.add(new FieldError("competence", "Competência fora do intervalo permitido"));
            }
            if (cmd.due_date() != null && cmd.due_date().isBefore(competence.minusMonths(1))) {
                errors.add(new FieldError("due_date", "Vencimento incoerente com a competência"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            journal.requireOpen(actor.organizationId(), competence);
            UUID id = Ids.newId();
            jdbc.sql("""
                            INSERT INTO expenses (id, organization_id, branch_id, category, subcategory, description, payee,
                                                  supplier_id, amount, competence, due_date, created_by)
                            VALUES (:id, :o, :b, :c, :s, :d, :p, :sup, :a, :comp, :due, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                    .param("c", cmd.category()).param("s", sub).param("d", desc).param("p", payee)
                    .param("sup", cmd.supplier_id()).param("a", amount).param("comp", competence).param("due", cmd.due_date())
                    .param("by", actor.userId()).update();
            audit.record(PayableService.entry(actor, "expenses.create", "expense", id, Map.of("category", cmd.category(),
                    "amount", amount.toPlainString(), "competence", competence.toString())));
            return view(find(id).orElseThrow());
        });
    }

    // ---------------- aprovar / recusar ----------------
    @Transactional
    public ExpenseView approve(Actor actor, UUID id, String idemKey, long expectedVersion, NoteCommand cmd) {
        Row x = lockVisible(actor, id, "approve");
        authz.requireAmountWithinLimit(actor, "expenses", "approve", x.amount());
        return idem.execute(actor, "expenses.approve", idemKey, Map.of("id", id.toString(), "version", expectedVersion),
                ExpenseView.class, () -> {
                    decide(actor, x, expectedVersion, "APPROVED", cmd == null ? null : Text.trim(cmd.note()));
                    String memo = x.subcategory() + " — " + x.description();
                    journal.post(actor, x.branchId(), "expense", x.id(), "EXPENSE_APPROVED", memo, null, x.competence(),
                            List.of(Line.debit(LEDGER.get(x.category()), x.amount()), Line.credit("FORNECEDORES", x.amount())));
                    UUID payable = payables.createForSource(actor, x.branchId(), "expense", x.id(), x.supplierId(),
                            (x.payee() != null ? x.payee() + " — " : "") + memo, x.competence(), x.amount(), x.dueDate());
                    jdbc.sql("UPDATE expenses SET payable_id = :p WHERE id = :id").param("p", payable).param("id", x.id()).update();
                    UUID bankTx = jdbc.sql("SELECT bank_transaction_id FROM expenses WHERE id = :id").param("id", x.id())
                            .query(UUID.class).optional().orElse(null);
                    if (bankTx != null) {
                        payables.settleFromBank(actor, payable, bankTx); // o débito já aconteceu no banco
                    }
                    audit.record(PayableService.entry(actor, "expenses.approve", "expense", x.id(),
                            Map.of("amount", x.amount().toPlainString())));
                    return view(find(x.id()).orElseThrow());
                });
    }

    @Transactional
    public ExpenseView reject(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row x = lockVisible(actor, id, "approve");
        String note = Text.trim(cmd == null ? null : cmd.note());
        if (note == null || note.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo da recusa.",
                    List.of(new FieldError("note", "Motivo obrigatório")));
        }
        decide(actor, x, expectedVersion, "REJECTED", note);
        // Sugestão do extrato recusada: a transação do banco volta para conferência (pode ser ignorada ou conciliada).
        jdbc.sql("""
                        UPDATE bank_transactions SET state = 'UNMATCHED', decided_note = :n, version = version + 1
                         WHERE suggested_expense_id = :e AND state = 'SUGGESTED'
                        """).param("n", "Sugestão recusada: " + note).param("e", x.id()).update();
        audit.record(PayableService.entry(actor, "expenses.reject", "expense", x.id(), Map.of("reason", note)));
        return view(find(x.id()).orElseThrow());
    }

    /** Estorno (FIN-08): lançamento inverso vinculado; conta a pagar cancelada (exige pagamento estornado antes). */
    @Transactional
    public ExpenseView reverse(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row x = lockVisible(actor, id, "reverse");
        String reason = Text.trim(cmd == null ? null : cmd.note());
        if (reason == null || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo do estorno.",
                    List.of(new FieldError("note", "Motivo obrigatório")));
        }
        if (!x.state().equals("APPROVED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só despesa aprovada é estornada (pendente: recuse).");
        }
        int n = jdbc.sql("""
                        UPDATE expenses SET state = 'REVERSED', reversed_by = :by, reversed_at = now(), reverse_reason = :r,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("r", reason).param("id", x.id()).param("v", expectedVersion)
                .update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A despesa foi alterada por outra pessoa. Recarregue.");
        }
        payables.cancelForSource(actor, "expense", x.id(), reason);
        journal.reverse(actor, "expense", x.id(), "EXPENSE_APPROVED", "EXPENSE_REVERSED", reason);
        audit.record(PayableService.entry(actor, "expenses.reverse", "expense", x.id(), Map.of("reason", reason)));
        return view(find(x.id()).orElseThrow());
    }

    private void decide(Actor actor, Row x, long expectedVersion, String state, String note) {
        if (!x.state().equals("PENDING")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Despesa já decidida (" + x.state() + ").");
        }
        if (x.createdBy().equals(actor.userId()) && !actor.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Quem cadastrou a despesa não a aprova.");
        }
        int n = jdbc.sql("""
                        UPDATE expenses SET state = :s, decided_by = :by, decided_at = now(), decided_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("s", state).param("by", actor.userId()).param("n", note).param("id", x.id())
                .param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A despesa foi alterada por outra pessoa. Recarregue.");
        }
    }

    // ---------------- consultas e anexos ----------------
    @Transactional(readOnly = true)
    public Page list(Actor actor, String state, String category, LocalDate from, LocalDate to, int limit, String cursor) {
        Decision d = authz.require(actor, "expenses", "view");
        Cursor.checkLimit(limit);
        if (state != null && !List.of("PENDING", "APPROVED", "REJECTED", "REVERSED").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        if (category != null && !LEDGER.containsKey(category)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "category inválida.");
        }
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(), COLUMNS);
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql(SELECT + " WHERE " + scope.sql()
                        + " AND (CAST(:st AS text) IS NULL OR x.state = CAST(:st AS text))"
                        + " AND (CAST(:cat AS text) IS NULL OR x.category = CAST(:cat AS text))"
                        + " AND (CAST(:f AS date) IS NULL OR x.competence >= CAST(:f AS date))"
                        + " AND (CAST(:t AS date) IS NULL OR x.competence < CAST(:t AS date))"
                        + " AND (CAST(:cts AS text) IS NULL OR (x.created_at, x.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))"
                        + " ORDER BY x.created_at DESC, x.id DESC LIMIT :lim")
                .param("st", state).param("cat", category).param("f", from).param("t", to)
                .param("cts", c == null ? null : c.sort()).param("cid", c == null ? null : c.id()).param("lim", limit + 1);
        for (Map.Entry<String, Object> e : scope.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<ExpenseView> rows = spec.query(Row.class).list().stream().map(this::view).toList();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().created_at().toString(), rows.getLast().id());
        }
        return new Page(rows, next);
    }

    @Transactional(readOnly = true)
    public ExpenseView get(Actor actor, UUID id) {
        return view(requireVisible(actor, id, "view"));
    }

    @Transactional
    public DocumentView attach(Actor actor, UUID id, String fileName, byte[] bytes) {
        Row x = requireVisible(actor, id, "view");
        if (!x.createdBy().equals(actor.userId()) && !actor.admin()) {
            authz.require(actor, "expenses", "approve");
        }
        return documents.attach(actor, x.branchId(), "expense", x.id(), fileName, bytes);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> attachments(Actor actor, UUID id) {
        return documents.list("expense", requireVisible(actor, id, "view").id());
    }

    private Row requireVisible(Actor actor, UUID id, String action) {
        Row x = find(id).filter(r -> r.organizationId().equals(actor.organizationId())).orElse(null);
        if (x == null) {
            authz.require(actor, "expenses", action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, "expenses", action, "expense", id.toString(), ref(x));
        return x;
    }

    private Row lockVisible(Actor actor, UUID id, String action) {
        jdbc.sql("SELECT id FROM expenses WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return requireVisible(actor, id, action);
    }

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE x.id = :id").param("id", id).query(Row.class).optional();
    }

    private static RecordRef ref(Row x) {
        return new RecordRef(x.organizationId(), x.branchId(), null, x.createdBy(), null);
    }

    private ExpenseView view(Row x) {
        return new ExpenseView(x.id(), x.category(), x.subcategory(), x.description(), x.payee(), x.supplierId(),
                x.supplierName(), Decimals.moneyStr(x.amount()), x.competence(), x.dueDate(), x.state(), x.payableId(),
                x.payableState(), Decimals.moneyStr(x.payableBalance()), x.branch(), x.createdByName(), x.createdAt(),
                x.decidedByName(), x.decidedAt(), x.decidedNote(), x.reverseReason(), x.version());
    }
}
