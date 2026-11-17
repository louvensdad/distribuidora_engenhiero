package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.finance.FinAccountService.Acc;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contas a pagar (FIN-02, FIN-06). Nascem do fato reconhecido (recebimento de mercadoria, gasto de veículo, despesa);
 * pagar baixa o passivo (FORNECEDORES) contra caixa/banco — nunca gera nova despesa. Devolução ao fornecedor abate o
 * saldo; excedente já pago vira crédito a receber do fornecedor. Estorno de pagamento devolve o saldo.
 * Ver: `expenses.view`; pagar: `expenses.confirm`; estornar pagamento: `expenses.reverse` (ADR-020, substitui ADR-017).
 */
@Service
public class PayableService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final Journal journal;
    private final FinAccountService accounts;

    public PayableService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                          Journal journal, FinAccountService accounts) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.journal = journal;
        this.accounts = accounts;
    }

    public record PayableView(UUID id, String source_type, UUID source_id, UUID supplier_id, String supplier_name,
                              String description, LocalDate competence, String amount, String balance,
                              LocalDate due_date, String state, String branch, OffsetDateTime created_at, long version) {
    }

    public record Page(List<PayableView> items, String next_cursor) {
    }

    public record PayCommand(String amount, UUID fin_account_id, LocalDate paid_on, String note) {
    }

    public record ReasonCommand(String reason) {
    }

    public record SettlementView(UUID id, UUID payable_id, String kind, String amount, UUID fin_account_id,
                                 String account_name, LocalDate paid_on, String note, String state, String created_by_name,
                                 OffsetDateTime created_at, String reverse_reason) {
    }

    public record Detail(PayableView payable, List<SettlementView> settlements) {
    }

    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("p.organization_id", "p.branch_id", null, "p.created_by", null);

    // ---------------- criação (pelos fatos de origem) ----------------
    /** Uma obrigação por origem (unicidade no banco). Valor zero (bonificação) não gera obrigação. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForReceipt(Actor actor, UUID branchId, UUID receiptId, UUID supplierId, String supplierName,
                                 String documentNumber, BigDecimal amount, LocalDate dueDate) {
        if (amount.signum() <= 0) {
            return null;
        }
        String desc = "Compra de mercadoria — " + supplierName + (documentNumber == null ? "" : " — " + documentNumber);
        return insert(actor, branchId, "inventory_receipt", receiptId, supplierId, desc, LocalDate.now(Journal.COMMERCIAL),
                amount, dueDate);
    }

    /** Obrigação genérica por origem (gasto de veículo pago pela empresa, despesa aprovada). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForSource(Actor actor, UUID branchId, String sourceType, UUID sourceId, String description,
                                BigDecimal amount, LocalDate dueDate) {
        return createForSource(actor, branchId, sourceType, sourceId, null, description, LocalDate.now(Journal.COMMERCIAL),
                amount, dueDate);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForSource(Actor actor, UUID branchId, String sourceType, UUID sourceId, UUID supplierId,
                                String description, LocalDate competence, BigDecimal amount, LocalDate dueDate) {
        return insert(actor, branchId, sourceType, sourceId, supplierId, description, competence, amount, dueDate);
    }

    private UUID insert(Actor actor, UUID branchId, String sourceType, UUID sourceId, UUID supplierId, String description,
                        LocalDate competence, BigDecimal amount, LocalDate dueDate) {
        UUID id = Ids.newId();
        String desc = description.length() > 200 ? description.substring(0, 200) : description;
        jdbc.sql("""
                        INSERT INTO payables (id, organization_id, branch_id, source_type, source_id, supplier_id, description,
                                              competence, amount, balance, due_date, created_by)
                        VALUES (:id, :o, :b, :st, :src, :sup, :d, :comp, :a, :a, :due, :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", branchId).param("st", sourceType)
                .param("src", sourceId).param("sup", supplierId).param("d", desc).param("comp", competence)
                .param("a", Decimals.money(amount)).param("due", dueDate).param("by", actor.userId()).update();
        audit.record(entry(actor, "payables.create", "payable", id, Map.of("source_type", sourceType,
                "source_id", sourceId.toString(), "amount", Decimals.moneyStr(amount))));
        return id;
    }

    // ---------------- pagamento ----------------
    @Transactional
    public Detail pay(Actor actor, UUID id, String idemKey, PayCommand cmd) {
        authz.require(actor, "expenses", "confirm");
        Map<String, Object> req = new HashMap<>();
        req.put("payable", id.toString());
        req.put("body", cmd);
        return idem.execute(actor, "payables.pay", idemKey, req, Detail.class, () -> {
            PRow p = lockVisible(actor, id, "confirm");
            if (!p.state().equals("OPEN") && !p.state().equals("PARTIALLY_PAID")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Conta " + p.state() + " não recebe pagamento.");
            }
            List<FieldError> errors = new ArrayList<>();
            BigDecimal amount = Decimals.parse(cmd == null ? null : cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
            Acc acc = accounts.moneyAccount(actor, cmd == null ? null : cmd.fin_account_id(), "fin_account_id", errors);
            LocalDate today = LocalDate.now(Journal.COMMERCIAL);
            LocalDate paidOn = cmd == null || cmd.paid_on() == null ? today : cmd.paid_on();
            if (paidOn.isAfter(today)) {
                errors.add(new FieldError("paid_on", "Pagamento não pode ser no futuro"));
            }
            String note = Text.trim(cmd == null ? null : cmd.note());
            if (amount != null && amount.compareTo(p.balance()) > 0) {
                errors.add(new FieldError("amount", "Maior que o saldo em aberto (" + Decimals.moneyStr(p.balance()) + ")"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            accounts.requireFunds(acc, amount, "amount");
            UUID sid = Ids.newId();
            jdbc.sql("""
                            INSERT INTO payable_settlements (id, payable_id, kind, amount, fin_account_id, paid_on, note, created_by)
                            VALUES (:id, :p, 'PAYMENT', :a, :f, :d, :n, :by)
                            """).param("id", sid).param("p", p.id()).param("a", amount).param("f", acc.id())
                    .param("d", paidOn).param("n", note).param("by", actor.userId()).update();
            // Baixa do passivo contra o dinheiro: nenhuma despesa nova (FIN-06).
            journal.post(actor, p.branchId(), "payable_payment", sid, "PAYABLE_PAYMENT", p.description(), null,
                    List.of(Line.debit("FORNECEDORES", amount), Line.credit(acc.ledgerAccount(), amount).at(acc.id())));
            applyBalance(p.id(), amount.negate());
            audit.record(entry(actor, "payables.pay", "payable", p.id(), Map.of("amount", amount.toPlainString(),
                    "account", acc.code())));
            return detail(p.id());
        });
    }

    /**
     * Pagamento chamado por guia/folha (o chamador já autorizou pela capacidade dele: `taxes.confirm`,
     * `payroll.confirm`). Mesma regra: baixa FORNECEDORES contra caixa/banco, sem nova despesa.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID payFor(Actor actor, UUID payableId, BigDecimal amount, UUID finAccountId, LocalDate paidOn, String note) {
        PRow p = jdbc.sql(SELECT + " WHERE p.id = :id FOR UPDATE OF p").param("id", payableId).query(PRow.class).single();
        if (!p.state().equals("OPEN") && !p.state().equals("PARTIALLY_PAID")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Conta " + p.state() + " não recebe pagamento.");
        }
        List<FieldError> errors = new ArrayList<>();
        Acc acc = accounts.moneyAccount(actor, finAccountId, "fin_account_id", errors);
        LocalDate today = LocalDate.now(Journal.COMMERCIAL);
        LocalDate on = paidOn == null ? today : paidOn;
        if (on.isAfter(today)) {
            errors.add(new FieldError("paid_on", "Pagamento não pode ser no futuro"));
        }
        if (amount.compareTo(p.balance()) > 0) {
            errors.add(new FieldError("amount", "Maior que o saldo em aberto (" + Decimals.moneyStr(p.balance()) + ")"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        accounts.requireFunds(acc, amount, "amount");
        UUID sid = Ids.newId();
        jdbc.sql("""
                        INSERT INTO payable_settlements (id, payable_id, kind, amount, fin_account_id, paid_on, note, created_by)
                        VALUES (:id, :p, 'PAYMENT', :a, :f, :d, :n, :by)
                        """).param("id", sid).param("p", p.id()).param("a", amount).param("f", acc.id())
                .param("d", on).param("n", note).param("by", actor.userId()).update();
        journal.post(actor, p.branchId(), "payable_payment", sid, "PAYABLE_PAYMENT", p.description(), null,
                List.of(Line.debit("FORNECEDORES", amount), Line.credit(acc.ledgerAccount(), amount).at(acc.id())));
        applyBalance(p.id(), amount.negate());
        return sid;
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(UUID payableId) {
        return jdbc.sql("SELECT balance FROM payables WHERE id = :id").param("id", payableId).query(BigDecimal.class).single();
    }

    @Transactional
    public Detail reverseSettlement(Actor actor, UUID settlementId, ReasonCommand cmd) {
        authz.require(actor, "expenses", "reverse");
        String reason = FinAccountService.requireReason(cmd == null ? null : cmd.reason());
        record S(UUID id, UUID payableId, String kind, BigDecimal amount, String state) {
        }
        S s = jdbc.sql("""
                        SELECT s.id, s.payable_id, s.kind, s.amount, s.state FROM payable_settlements s
                          JOIN payables p ON p.id = s.payable_id WHERE s.id = :id AND p.organization_id = :o FOR UPDATE OF s
                        """).param("id", settlementId).param("o", actor.organizationId()).query(S.class).optional()
                .orElseThrow(ApiException::notFound);
        PRow p = lockVisible(actor, s.payableId(), "reverse");
        if (!s.kind().equals("PAYMENT") || !s.state().equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só pagamento confirmado pode ser estornado.");
        }
        if (p.state().equals("CANCELLED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Conta cancelada.");
        }
        jdbc.sql("""
                        UPDATE payable_settlements SET state = 'REVERSED', reversed_by = :by, reversed_at = now(),
                               reverse_reason = :r WHERE id = :id
                        """).param("by", actor.userId()).param("r", reason).param("id", s.id()).update();
        journal.reverse(actor, "payable_payment", s.id(), "PAYABLE_PAYMENT", "PAYABLE_PAYMENT_REVERSED", reason);
        applyBalance(p.id(), s.amount());
        // Se esse pagamento estava conciliado com o extrato, a transação do banco volta para conferência.
        jdbc.sql("""
                        UPDATE bank_transactions SET state = 'UNMATCHED', matched_type = NULL, matched_id = NULL,
                               decided_note = 'Pagamento conciliado foi estornado', version = version + 1
                         WHERE matched_type = 'payable_settlement' AND matched_id = :s
                        """).param("s", s.id()).update();
        audit.record(entry(actor, "payables.payment_reverse", "payable", p.id(), Map.of("amount", s.amount().toPlainString(),
                "reason", reason)));
        return detail(p.id());
    }

    /**
     * Despesa que veio do extrato bancário (Open Finance): o dinheiro já saiu daquela conta na data do banco. A
     * aprovação registra o pagamento junto, e a transação do banco fica conciliada com ele (uma única baixa).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleFromBank(Actor actor, UUID payableId, UUID bankTransactionId) {
        record T(UUID finAccountId, BigDecimal amount, LocalDate bookedOn, String ledger) {
        }
        T t = jdbc.sql("""
                        SELECT b.fin_account_id, b.amount, b.booked_on, a.ledger_account AS ledger FROM bank_transactions b
                          JOIN fin_accounts a ON a.id = b.fin_account_id WHERE b.id = :id FOR UPDATE OF b
                        """).param("id", bankTransactionId).query(T.class).single();
        PRow p = jdbc.sql(SELECT + " WHERE p.id = :id FOR UPDATE OF p").param("id", payableId).query(PRow.class).single();
        UUID sid = Ids.newId();
        jdbc.sql("""
                        INSERT INTO payable_settlements (id, payable_id, kind, amount, fin_account_id, paid_on, note, source_id, created_by)
                        VALUES (:id, :p, 'PAYMENT', :a, :f, :d, 'Débito no banco (Open Finance)', :src, :by)
                        """).param("id", sid).param("p", p.id()).param("a", t.amount()).param("f", t.finAccountId())
                .param("d", t.bookedOn()).param("src", bankTransactionId).param("by", actor.userId()).update();
        journal.post(actor, p.branchId(), "payable_payment", sid, "PAYABLE_PAYMENT", p.description(), null,
                List.of(Line.debit("FORNECEDORES", t.amount()), Line.credit(t.ledger(), t.amount()).at(t.finAccountId())));
        applyBalance(p.id(), t.amount().negate());
        jdbc.sql("""
                        UPDATE bank_transactions SET state = 'MATCHED', matched_type = 'payable_settlement', matched_id = :s,
                               decided_by = :by, version = version + 1 WHERE id = :id
                        """).param("s", sid).param("by", actor.userId()).param("id", bankTransactionId).update();
    }

    // ---------------- abatimentos (chamados pelos fatos de origem) ----------------
    /**
     * Devolução ao fornecedor: o livro já debitou FORNECEDORES; aqui o saldo da conta a pagar do recebimento é abatido.
     * O que exceder o saldo (já pago) vira crédito a receber do fornecedor.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void supplierReturn(Actor actor, UUID branchId, UUID receiptId, UUID supplierId, UUID returnId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        Optional<PRow> p = jdbc.sql(SELECT + " WHERE p.source_type = 'inventory_receipt' AND p.source_id = :r FOR UPDATE OF p")
                .param("r", receiptId).query(PRow.class).optional();
        BigDecimal applied = p.filter(x -> !x.state().equals("CANCELLED")).map(x -> x.balance().min(amount))
                .orElse(BigDecimal.ZERO);
        if (applied.signum() > 0) {
            jdbc.sql("""
                            INSERT INTO payable_settlements (id, payable_id, kind, amount, paid_on, note, source_id, created_by)
                            VALUES (:id, :p, 'SUPPLIER_RETURN', :a, :d, 'Devolução ao fornecedor', :src, :by)
                            """).param("id", Ids.newId()).param("p", p.get().id()).param("a", applied)
                    .param("d", LocalDate.now(Journal.COMMERCIAL)).param("src", returnId).param("by", actor.userId()).update();
            applyBalance(p.get().id(), applied.negate());
        }
        BigDecimal excess = amount.subtract(applied);
        if (excess.signum() > 0) {
            jdbc.sql("""
                            INSERT INTO receivables (id, organization_id, branch_id, kind, supplier_id, due_date, gross, amount,
                                                     balance, description)
                            VALUES (:id, :o, :b, 'SUPPLIER_CREDIT', :s, :d, :a, :a, :a, 'Crédito por devolução ao fornecedor')
                            """).param("id", Ids.newId()).param("o", actor.organizationId()).param("b", branchId)
                    .param("s", supplierId).param("d", LocalDate.now(Journal.COMMERCIAL)).param("a", excess).update();
        }
    }

    /** Estorno da despesa de origem: cancela o saldo em aberto (sem pagamento confirmado). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForSource(Actor actor, String sourceType, UUID sourceId, String reason) {
        PRow p = jdbc.sql(SELECT + " WHERE p.source_type = :st AND p.source_id = :s FOR UPDATE OF p")
                .param("st", sourceType).param("s", sourceId).query(PRow.class).optional().orElse(null);
        if (p == null) {
            return;
        }
        int paid = jdbc.sql("SELECT count(*) FROM payable_settlements WHERE payable_id = :p AND kind = 'PAYMENT' AND state = 'CONFIRMED'")
                .param("p", p.id()).query(Integer.class).single();
        if (paid > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "A conta a pagar já tem pagamento: estorne o pagamento antes.");
        }
        if (p.balance().signum() > 0) {
            jdbc.sql("""
                            INSERT INTO payable_settlements (id, payable_id, kind, amount, paid_on, note, created_by)
                            VALUES (:id, :p, 'CANCELLATION', :a, :d, :n, :by)
                            """).param("id", Ids.newId()).param("p", p.id()).param("a", p.balance())
                    .param("d", LocalDate.now(Journal.COMMERCIAL)).param("n", reason).param("by", actor.userId()).update();
        }
        jdbc.sql("UPDATE payables SET balance = 0, state = 'CANCELLED', version = version + 1 WHERE id = :id")
                .param("id", p.id()).update();
    }

    // ---------------- consultas ----------------
    @Transactional(readOnly = true)
    public Page list(Actor actor, String state, int limit, String cursor) {
        Decision d = authz.require(actor, "expenses", "view");
        Cursor.checkLimit(limit);
        if (state != null && !List.of("OPEN", "PARTIALLY_PAID", "PAID", "CANCELLED", "PENDING").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(), COLUMNS);
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql(SELECT + " WHERE " + scope.sql()
                        + " AND (CAST(:st AS text) IS NULL OR p.state = CAST(:st AS text)"
                        + "      OR (CAST(:st AS text) = 'PENDING' AND p.state IN ('OPEN', 'PARTIALLY_PAID')))"
                        + " AND (CAST(:cts AS text) IS NULL OR (p.created_at, p.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))"
                        + " ORDER BY p.created_at DESC, p.id DESC LIMIT :lim")
                .param("st", state).param("cts", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1);
        for (Map.Entry<String, Object> e : scope.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<PayableView> rows = spec.query(PRow.class).list().stream().map(PayableService::view).toList();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().created_at().toString(), rows.getLast().id());
        }
        return new Page(rows, next);
    }

    @Transactional(readOnly = true)
    public Detail get(Actor actor, UUID id) {
        PRow p = find(id).filter(x -> x.organizationId().equals(actor.organizationId())).orElse(null);
        if (p == null) {
            authz.require(actor, "expenses", "view");
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, "expenses", "view", "payable", id.toString(), ref(p));
        return detail(id);
    }

    // ---------------- apoio ----------------
    record PRow(UUID id, UUID organizationId, UUID branchId, String sourceType, UUID sourceId, UUID supplierId,
                String supplierName, String description, LocalDate competence, BigDecimal amount, BigDecimal balance,
                LocalDate dueDate, String state, String branch, OffsetDateTime createdAt, UUID createdBy, long version) {
    }

    static final String SELECT = """
            SELECT p.id, p.organization_id, p.branch_id, p.source_type, p.source_id, p.supplier_id, s.name AS supplier_name,
                   p.description, p.competence, p.amount, p.balance, p.due_date, p.state, b.name AS branch, p.created_at,
                   p.created_by, p.version
              FROM payables p JOIN branches b ON b.id = p.branch_id LEFT JOIN suppliers s ON s.id = p.supplier_id
            """;

    private Optional<PRow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(PRow.class).optional();
    }

    private PRow lockVisible(Actor actor, UUID id, String action) {
        PRow p = jdbc.sql(SELECT + " WHERE p.id = :id FOR UPDATE OF p").param("id", id).query(PRow.class).optional()
                .filter(x -> x.organizationId().equals(actor.organizationId())).orElse(null);
        if (p == null) {
            authz.require(actor, "expenses", action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, "expenses", action, "payable", id.toString(), ref(p));
        return p;
    }

    private void applyBalance(UUID id, BigDecimal delta) {
        jdbc.sql("""
                        UPDATE payables SET balance = balance + :d,
                               state = CASE WHEN balance + :d = 0 THEN 'PAID' WHEN balance + :d < amount THEN 'PARTIALLY_PAID'
                                            ELSE 'OPEN' END,
                               version = version + 1 WHERE id = :id
                        """).param("d", delta).param("id", id).update();
    }

    private Detail detail(UUID id) {
        List<SettlementView> s = jdbc.sql("""
                        SELECT s.id, s.payable_id, s.kind, CAST(s.amount AS text) AS amount, s.fin_account_id,
                               a.name AS account_name, s.paid_on, s.note, s.state, u.display_name AS created_by_name,
                               s.created_at, s.reverse_reason
                          FROM payable_settlements s JOIN app_users u ON u.id = s.created_by
                          LEFT JOIN fin_accounts a ON a.id = s.fin_account_id
                         WHERE s.payable_id = :p ORDER BY s.created_at, s.id
                        """).param("p", id).query(SettlementView.class).list();
        return new Detail(view(find(id).orElseThrow()), s);
    }

    static PayableView view(PRow p) {
        return new PayableView(p.id(), p.sourceType(), p.sourceId(), p.supplierId(), p.supplierName(), p.description(),
                p.competence(), Decimals.moneyStr(p.amount()), Decimals.moneyStr(p.balance()), p.dueDate(), p.state(),
                p.branch(), p.createdAt(), p.version());
    }

    static RecordRef ref(PRow p) {
        return new RecordRef(p.organizationId(), p.branchId(), null, p.createdBy(), null);
    }

    static AuditEntry entry(Actor actor, String action, String type, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                type, id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
