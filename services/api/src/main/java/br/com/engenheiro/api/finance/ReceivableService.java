package br.com.engenheiro.api.finance;

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
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Contas a receber (FIN-02, FIN-03):
 * - CARD: venda no cartão vira recebível LÍQUIDO no adquirente, em parcelas (bruto, taxa e líquido por parcela; soma
 *   idêntica à venda; diferença de centavos na última). A taxa já foi despesa na venda — a liquidação não a repete;
 *   diferença real no repasse só com motivo (ajuste de taxa).
 * - CUSTOMER: venda a prazo autorizada (vencimento do pagamento ON_ACCOUNT); liquidar baixa CLIENTES.
 * - SUPPLIER_CREDIT: devolução ao fornecedor acima do saldo a pagar.
 * Liquidar/estornar: `payments.confirm` (crédito de fornecedor: `expenses.confirm`); ver: `payments.view` ou `expenses.view`.
 */
@Service
public class ReceivableService {
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("r.organization_id", "r.branch_id", null, null, null);
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final Journal journal;
    private final FinAccountService accounts;

    public ReceivableService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                             Journal journal, FinAccountService accounts) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.journal = journal;
        this.accounts = accounts;
    }

    public record SettleCommand(String amount, String received, UUID fin_account_id, LocalDate received_on, String note) {
    }

    public record ReasonCommand(String reason) {
    }

    public record ReceivableView(UUID id, String kind, UUID payment_id, UUID order_id, Long order_number,
                                 String customer_name, UUID supplier_id, String supplier_name, int installment_no,
                                 int installments, LocalDate due_date, String gross, String fee, String amount,
                                 String balance, String state, String description, String branch, long version) {
    }

    public record SettlementView(UUID id, String amount, String received, UUID fin_account_id, String account_name,
                                 LocalDate received_on, String note, String state, String created_by_name,
                                 OffsetDateTime created_at, String reverse_reason) {
    }

    public record Detail(ReceivableView receivable, List<SettlementView> settlements) {
    }

    public record Summary(String open_customer, String open_card, String open_supplier_credit, String overdue) {
    }

    // ---------------- criação (pelos pagamentos) ----------------
    /** Cartão confirmado: agenda de parcelas líquidas (débito D+1; crédito D+30 por parcela). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createForCard(UUID org, UUID branch, UUID paymentId, UUID orderId, String method, BigDecimal gross,
                              BigDecimal fee, int installments, String reference) {
        int n = Math.max(1, installments);
        LocalDate today = LocalDate.now(Journal.COMMERCIAL);
        BigDecimal gPart = gross.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        BigDecimal fPart = fee.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        for (int i = 1; i <= n; i++) {
            BigDecimal g = i < n ? gPart : gross.subtract(gPart.multiply(BigDecimal.valueOf(n - 1)));
            BigDecimal f = i < n ? fPart : fee.subtract(fPart.multiply(BigDecimal.valueOf(n - 1)));
            jdbc.sql("""
                            INSERT INTO receivables (id, organization_id, branch_id, kind, payment_id, order_id, installment_no,
                                                     installments, due_date, gross, fee, amount, balance, description)
                            VALUES (:id, :o, :b, 'CARD', :p, :ord, :i, :n, :due, :g, :f, :net, :net, :d)
                            """).param("id", Ids.newId()).param("o", org).param("b", branch).param("p", paymentId)
                    .param("ord", orderId).param("i", i).param("n", n)
                    .param("due", method.equals("DEBIT") ? today.plusDays(1) : today.plusDays(30L * i))
                    .param("g", g).param("f", f).param("net", g.subtract(f))
                    .param("d", "Cartão " + (method.equals("DEBIT") ? "débito" : "crédito") + (reference == null ? "" : " NSU " + reference))
                    .update();
        }
    }

    /** A prazo autorizado: um recebível do cliente com o vencimento combinado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createForOnAccount(UUID org, UUID branch, UUID paymentId, UUID orderId, BigDecimal amount, LocalDate due) {
        jdbc.sql("""
                        INSERT INTO receivables (id, organization_id, branch_id, kind, payment_id, order_id, due_date, gross,
                                                 amount, balance, description)
                        VALUES (:id, :o, :b, 'CUSTOMER', :p, :ord, :due, :a, :a, :a, 'Venda a prazo')
                        """).param("id", Ids.newId()).param("o", org).param("b", branch).param("p", paymentId)
                .param("ord", orderId).param("due", due).param("a", amount).update();
    }

    /** Estorno do pagamento de origem: cancela os recebíveis ainda sem liquidação; liquidado → estorne a liquidação antes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForPayment(UUID paymentId) {
        int settled = jdbc.sql("""
                        SELECT count(*) FROM receivable_settlements s JOIN receivables r ON r.id = s.receivable_id
                         WHERE r.payment_id = :p AND s.state = 'CONFIRMED'
                        """).param("p", paymentId).query(Integer.class).single();
        if (settled > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "Há parcela já liquidada deste pagamento: estorne a liquidação antes.");
        }
        jdbc.sql("UPDATE receivables SET state = 'CANCELLED', balance = 0, version = version + 1 WHERE payment_id = :p")
                .param("p", paymentId).update();
    }

    // ---------------- liquidação (conciliação do repasse/recebimento) ----------------
    @Transactional
    public Detail settle(Actor actor, UUID id, String idemKey, SettleCommand cmd) {
        Map<String, Object> req = new HashMap<>();
        req.put("receivable", id.toString());
        req.put("body", cmd);
        RRow r0 = find(id).filter(x -> x.organizationId().equals(actor.organizationId())).orElse(null);
        String resource = r0 != null && r0.kind().equals("SUPPLIER_CREDIT") ? "expenses" : "payments";
        authz.require(actor, resource, "confirm");
        return idem.execute(actor, "receivables.settle", idemKey, req, Detail.class, () -> {
            RRow r = lockVisible(actor, id, resource, "confirm");
            if (!r.state().equals("OPEN") && !r.state().equals("PARTIALLY_SETTLED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Recebível " + r.state() + ".");
            }
            List<FieldError> errors = new ArrayList<>();
            BigDecimal amount = cmd == null || cmd.amount() == null || cmd.amount().isBlank() ? r.balance()
                    : Decimals.parse(cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
            BigDecimal received = cmd == null || cmd.received() == null || cmd.received().isBlank() ? amount
                    : Decimals.parse(cmd.received(), Decimals.MONEY_SCALE, true, "received", errors);
            Acc acc = accounts.moneyAccount(actor, cmd == null ? null : cmd.fin_account_id(), "fin_account_id", errors);
            if (acc != null && r.kind().equals("CARD") && !acc.ledgerAccount().equals("BANCO")) {
                errors.add(new FieldError("fin_account_id", "Repasse do cartão entra em conta bancária"));
            }
            LocalDate today = LocalDate.now(Journal.COMMERCIAL);
            LocalDate on = cmd == null || cmd.received_on() == null ? today : cmd.received_on();
            if (on.isAfter(today)) {
                errors.add(new FieldError("received_on", "Data no futuro"));
            }
            String note = Text.trim(cmd == null ? null : cmd.note());
            if (amount != null && amount.compareTo(r.balance()) > 0) {
                errors.add(new FieldError("amount", "Maior que o saldo (" + Decimals.moneyStr(r.balance()) + ")"));
            }
            if (amount != null && received != null) {
                int cmp = received.compareTo(amount);
                if (cmp > 0) {
                    errors.add(new FieldError("received", "Recebido maior que a baixa: registre o excedente à parte"));
                } else if (cmp < 0 && !r.kind().equals("CARD")) {
                    errors.add(new FieldError("received", "Recebido deve ser igual à baixa"));
                } else if (cmp < 0 && (note == null || note.length() > 500)) {
                    errors.add(new FieldError("note", "Diferença no repasse exige o motivo (ajuste de taxa)"));
                }
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            UUID sid = Ids.newId();
            jdbc.sql("""
                            INSERT INTO receivable_settlements (id, receivable_id, amount, received, fin_account_id, received_on,
                                                                note, created_by)
                            VALUES (:id, :r, :a, :rec, :f, :d, :n, :by)
                            """).param("id", sid).param("r", r.id()).param("a", amount).param("rec", received)
                    .param("f", acc.id()).param("d", on).param("n", note).param("by", actor.userId()).update();
            BigDecimal diff = amount.subtract(received);
            String ledger = switch (r.kind()) {
                case "CARD" -> "RECEBIVEL_CARTAO";
                case "CUSTOMER" -> "CLIENTES";
                default -> "FORNECEDORES";
            };
            journal.post(actor, r.branchId(), "receivable_settlement", sid, "RECEIVABLE_SETTLED",
                    (r.description() == null ? "Recebível" : r.description()) + " " + r.installmentNo() + "/" + r.installments(),
                    null, List.of(Line.debit(acc.ledgerAccount(), received).at(acc.id()),
                            Line.debit("TAXAS_CARTAO", diff), Line.credit(ledger, amount)));
            applyBalance(r.id(), amount.negate());
            audit.record(PayableService.entry(actor, "receivables.settle", "receivable", r.id(),
                    Map.of("amount", amount.toPlainString(), "received", received.toPlainString(), "account", acc.code())));
            return detail(r.id());
        });
    }

    @Transactional
    public Detail reverseSettlement(Actor actor, UUID settlementId, ReasonCommand cmd) {
        String reason = FinAccountService.requireReason(cmd == null ? null : cmd.reason());
        record S(UUID id, UUID receivableId, BigDecimal amount, String state) {
        }
        S s = jdbc.sql("""
                        SELECT s.id, s.receivable_id, s.amount, s.state FROM receivable_settlements s
                          JOIN receivables r ON r.id = s.receivable_id WHERE s.id = :id AND r.organization_id = :o
                           FOR UPDATE OF s
                        """).param("id", settlementId).param("o", actor.organizationId()).query(S.class).optional()
                .orElseThrow(ApiException::notFound);
        RRow r = find(s.receivableId()).orElseThrow();
        r = lockVisible(actor, r.id(), r.kind().equals("SUPPLIER_CREDIT") ? "expenses" : "payments", "confirm");
        if (!s.state().equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Liquidação já estornada.");
        }
        jdbc.sql("""
                        UPDATE receivable_settlements SET state = 'REVERSED', reversed_by = :by, reversed_at = now(),
                               reverse_reason = :r WHERE id = :id
                        """).param("by", actor.userId()).param("r", reason).param("id", s.id()).update();
        journal.reverse(actor, "receivable_settlement", s.id(), "RECEIVABLE_SETTLED", "RECEIVABLE_SETTLEMENT_REVERSED", reason);
        applyBalance(r.id(), s.amount());
        audit.record(PayableService.entry(actor, "receivables.settlement_reverse", "receivable", r.id(),
                Map.of("amount", s.amount().toPlainString(), "reason", reason)));
        return detail(r.id());
    }

    // ---------------- consultas ----------------
    @Transactional(readOnly = true)
    public List<ReceivableView> list(Actor actor, String kind, String state) {
        if (kind != null && !List.of("CUSTOMER", "CARD", "SUPPLIER_CREDIT").contains(kind)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "kind inválido.");
        }
        if (state != null && !List.of("OPEN", "PARTIALLY_SETTLED", "SETTLED", "CANCELLED", "PENDING").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        Decision pay = authz.decide(actor, "payments", "view");
        Decision exp = authz.decide(actor, "expenses", "view");
        if (!pay.allowed() && !exp.allowed()) {
            authz.require(actor, "payments", "view");
        }
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (pay.allowed()) {
            ScopeSql.Predicate p = ScopeSql.of(actor, pay.scopes(), COLUMNS);
            parts.add("(r.kind IN ('CUSTOMER', 'CARD') AND " + p.sql() + ")");
            params.putAll(p.params());
        }
        if (exp.allowed()) {
            ScopeSql.Predicate p = ScopeSql.of(actor, exp.scopes(), COLUMNS);
            parts.add("(r.kind = 'SUPPLIER_CREDIT' AND " + p.sql() + ")");
            params.putAll(p.params());
        }
        var spec = jdbc.sql(SELECT + " WHERE (" + String.join(" OR ", parts) + ")"
                        + " AND (CAST(:k AS text) IS NULL OR r.kind = CAST(:k AS text))"
                        + " AND (CAST(:st AS text) IS NULL OR r.state = CAST(:st AS text)"
                        + "      OR (CAST(:st AS text) = 'PENDING' AND r.state IN ('OPEN', 'PARTIALLY_SETTLED')))"
                        + " ORDER BY r.due_date, r.created_at, r.installment_no LIMIT 300")
                .param("k", kind).param("st", state);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(RRow.class).list().stream().map(ReceivableService::view).toList();
    }

    @Transactional(readOnly = true)
    public Detail get(Actor actor, UUID id) {
        RRow r = find(id).filter(x -> x.organizationId().equals(actor.organizationId())).orElse(null);
        String resource = r != null && r.kind().equals("SUPPLIER_CREDIT") ? "expenses" : "payments";
        if (r == null) {
            authz.require(actor, resource, "view");
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, resource, "view", "receivable", id.toString(), ref(r));
        return detail(id);
    }

    // ---------------- apoio ----------------
    record RRow(UUID id, UUID organizationId, UUID branchId, String kind, UUID paymentId, UUID orderId, Long orderNumber,
                String customerName, UUID supplierId, String supplierName, int installmentNo, int installments,
                LocalDate dueDate, BigDecimal gross, BigDecimal fee, BigDecimal amount, BigDecimal balance, String state,
                String description, String branch, long version) {
    }

    static final String SELECT = """
            SELECT r.id, r.organization_id, r.branch_id, r.kind, r.payment_id, r.order_id, o.number AS order_number,
                   c.name AS customer_name, r.supplier_id, s.name AS supplier_name, r.installment_no, r.installments,
                   r.due_date, r.gross, r.fee, r.amount, r.balance, r.state, r.description, b.name AS branch, r.version
              FROM receivables r JOIN branches b ON b.id = r.branch_id LEFT JOIN orders o ON o.id = r.order_id
              LEFT JOIN customers c ON c.id = o.customer_id LEFT JOIN suppliers s ON s.id = r.supplier_id
            """;

    private Optional<RRow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(RRow.class).optional();
    }

    private RRow lockVisible(Actor actor, UUID id, String resource, String action) {
        jdbc.sql("SELECT id FROM receivables WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        RRow r = find(id).filter(x -> x.organizationId().equals(actor.organizationId())).orElse(null);
        if (r == null) {
            authz.require(actor, resource, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, resource, action, "receivable", id.toString(), ref(r));
        return r;
    }

    private void applyBalance(UUID id, BigDecimal delta) {
        jdbc.sql("""
                        UPDATE receivables SET balance = balance + :d,
                               state = CASE WHEN balance + :d = 0 THEN 'SETTLED' WHEN balance + :d < amount THEN 'PARTIALLY_SETTLED'
                                            ELSE 'OPEN' END,
                               version = version + 1 WHERE id = :id
                        """).param("d", delta).param("id", id).update();
    }

    private Detail detail(UUID id) {
        List<SettlementView> s = jdbc.sql("""
                        SELECT s.id, CAST(s.amount AS text) AS amount, CAST(s.received AS text) AS received, s.fin_account_id,
                               a.name AS account_name, s.received_on, s.note, s.state, u.display_name AS created_by_name,
                               s.created_at, s.reverse_reason
                          FROM receivable_settlements s JOIN fin_accounts a ON a.id = s.fin_account_id
                          JOIN app_users u ON u.id = s.created_by WHERE s.receivable_id = :r ORDER BY s.created_at
                        """).param("r", id).query(SettlementView.class).list();
        return new Detail(view(find(id).orElseThrow()), s);
    }

    static ReceivableView view(RRow r) {
        return new ReceivableView(r.id(), r.kind(), r.paymentId(), r.orderId(), r.orderNumber(), r.customerName(),
                r.supplierId(), r.supplierName(), r.installmentNo(), r.installments(), r.dueDate(), Decimals.moneyStr(r.gross()),
                Decimals.moneyStr(r.fee()), Decimals.moneyStr(r.amount()), Decimals.moneyStr(r.balance()), r.state(),
                r.description(), r.branch(), r.version());
    }

    static RecordRef ref(RRow r) {
        return new RecordRef(r.organizationId(), r.branchId(), null, null, null);
    }
}
