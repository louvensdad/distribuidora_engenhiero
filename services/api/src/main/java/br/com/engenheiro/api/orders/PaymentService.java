package br.com.engenheiro.api.orders;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.finance.Journal.Line;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.orders.OrderService.OrderView;
import br.com.engenheiro.api.orders.OrderService.Row;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recebimentos de cliente (ORD-03..06, ORD-08, ORD-10). Situação do pagamento é separada da do pedido.
 * - Dinheiro: caixa aberto do operador, valor apresentado ≥ valor; entra no caixa só o valor do pedido (troco não é
 *   receita nem despesa).
 * - Pix/transferência: comprovante não confirma; fica PENDING até conferência manual autorizada. Confirmação por
 *   provedor: NÃO CONFIGURADO (503), sem sucesso simulado.
 * - Cartão: bruto, taxa (despesa, uma vez), líquido a receber do adquirente, NSU único.
 * - Dividido: a soma dos meios precisa ser exatamente o saldo devedor. A prazo: só administrador, com vencimento (D15).
 * Pagamento antes da entrega credita CLIENTES (adiantamento); a entrega reconhece a venda sem nova entrada de caixa.
 */
@Service
public class PaymentService {
    static final List<String> METHODS = List.of("CASH", "PIX", "DEBIT", "CREDIT", "BANK_TRANSFER", "ON_ACCOUNT");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final OrderService orders;
    private final CashService cash;
    private final Journal journal;

    public PaymentService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                          OrderService orders, CashService cash, Journal journal) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.orders = orders;
        this.cash = cash;
        this.journal = journal;
    }

    public record Part(String method, String amount, String confirmation_kind, String presented_cash, String fee,
                       Integer installments, String provider_reference, UUID document_id, String due_date) {
    }

    public record RegisterCommand(List<Part> parts, String notes) {
    }

    public record NoteCommand(String note) {
    }

    public record PaymentView(UUID id, UUID order_id, String method, String state, String confirmation_kind,
                              String amount, String fee, String net, String presented_cash, String change_given,
                              Integer installments, String provider_reference, UUID document_id, LocalDate due_date,
                              String created_by_name, OffsetDateTime created_at, String confirmed_by_name,
                              OffsetDateTime confirmed_at, String decided_note, long version) {
    }

    public record RegisterResult(List<PaymentView> payments, OrderView order) {
    }

    record PRow(UUID id, UUID organizationId, UUID branchId, UUID orderId, String method, String state,
                String confirmationKind, BigDecimal amount, BigDecimal fee, BigDecimal net, BigDecimal presentedCash,
                BigDecimal changeGiven, Integer installments, String providerReference, UUID documentId,
                LocalDate dueDate, UUID cashSessionId, String createdByName, OffsetDateTime createdAt,
                String confirmedByName, OffsetDateTime confirmedAt, String decidedNote, long version) {
    }

    // ---------------- registrar (um ou vários meios) ----------------
    @Transactional
    public RegisterResult register(Actor actor, UUID orderId, String idemKey, RegisterCommand cmd) {
        Row o = orders.lockForPayment(actor, orderId);
        Map<String, Object> req = new HashMap<>();
        req.put("order", orderId.toString());
        req.put("body", cmd);
        return idem.execute(actor, "payments.register", idemKey, req, RegisterResult.class,
                () -> doRegister(actor, o, cmd, null));
    }

    /**
     * Recebimento na entrega (LOG-07): a viagem já autorizou o acesso ao pedido (`trips.confirm` no escopo da viagem);
     * as capacidades por meio de pagamento continuam valendo. O dinheiro vai para o caixa aberto do motorista.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RegisterResult registerForTrip(Actor actor, UUID orderId, UUID tripId, RegisterCommand cmd) {
        Row o = orders.lockForTrip(orderId);
        return doRegister(actor, o, cmd, tripId);
    }

    private RegisterResult doRegister(Actor actor, Row o, RegisterCommand cmd, UUID tripId) {
        if (o.state().equals("CANCELLED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Pedido cancelado não recebe pagamento.");
        }
        List<Part> parts = cmd == null || cmd.parts() == null ? List.of() : cmd.parts();
        if (parts.isEmpty() || parts.size() > 5) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe de 1 a 5 meios de pagamento.",
                    List.of(new FieldError("parts", "Obrigatório")));
        }
        String notes = cmd.notes() == null ? null : Text.trim(cmd.notes());
        List<FieldError> errors = new ArrayList<>();
        record Valid(Part p, BigDecimal amount, BigDecimal presented, BigDecimal fee, LocalDate due) {
        }
        List<Valid> valid = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            String f = "parts[" + i + "]";
            if (p.method() == null || !METHODS.contains(p.method())) {
                errors.add(new FieldError(f + ".method", "Meio inválido"));
                continue;
            }
            BigDecimal amount = Decimals.parse(p.amount(), Decimals.MONEY_SCALE, false, f + ".amount", errors);
            BigDecimal presented = null;
            BigDecimal fee = BigDecimal.ZERO;
            LocalDate due = null;
            String kind = p.confirmation_kind() == null ? "MANUAL_AUTHORIZED" : p.confirmation_kind();
            if (!kind.equals("MANUAL_AUTHORIZED") && !kind.equals("PROVIDER")) {
                errors.add(new FieldError(f + ".confirmation_kind", "Use MANUAL_AUTHORIZED ou PROVIDER"));
            }
            if (kind.equals("PROVIDER")) {
                throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE, "Provedor de pagamentos NÃO CONFIGURADO (D04). "
                        + "Use conferência manual autorizada; nada foi registrado.");
            }
            switch (p.method()) {
                case "CASH" -> {
                    presented = Decimals.parse(p.presented_cash(), Decimals.MONEY_SCALE, false, f + ".presented_cash", errors);
                    if (presented != null && amount != null && presented.compareTo(amount) < 0) {
                        errors.add(new FieldError(f + ".presented_cash", "Valor apresentado menor que o valor a pagar"));
                    }
                }
                case "DEBIT", "CREDIT" -> {
                    String ref = Text.trim(p.provider_reference());
                    if (ref == null) {
                        errors.add(new FieldError(f + ".provider_reference", "Informe o NSU/código da transação"));
                    }
                    BigDecimal fe = Decimals.parseOptional(p.fee(), Decimals.MONEY_SCALE, f + ".fee", errors);
                    fee = fe == null ? BigDecimal.ZERO.setScale(2) : fe;
                    if (amount != null && fee.compareTo(amount) >= 0) {
                        errors.add(new FieldError(f + ".fee", "Taxa deve ser menor que o valor"));
                    }
                    int inst = p.installments() == null ? 1 : p.installments();
                    if (inst < 1 || inst > 24 || (p.method().equals("DEBIT") && inst != 1)) {
                        errors.add(new FieldError(f + ".installments", "Parcelas inválidas"));
                    }
                }
                case "ON_ACCOUNT" -> {
                    try {
                        due = p.due_date() == null ? null : LocalDate.parse(p.due_date());
                    } catch (RuntimeException e) {
                        due = null;
                    }
                    if (due == null) {
                        errors.add(new FieldError(f + ".due_date", "Vencimento obrigatório (AAAA-MM-DD)"));
                    }
                }
                default -> {
                }
            }
            if (p.document_id() != null) {
                int ok = jdbc.sql("SELECT count(*) FROM documents WHERE id = :d AND owner_type = 'order' AND owner_id = :o")
                        .param("d", p.document_id()).param("o", o.id()).query(Integer.class).single();
                if (ok == 0) {
                    errors.add(new FieldError(f + ".document_id", "Comprovante não pertence a este pedido"));
                }
            }
            if (amount != null) {
                sum = sum.add(amount);
            }
            valid.add(new Valid(p, amount, presented, fee, due));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os meios de pagamento.", errors);
        }
        BigDecimal due = orders.effectiveTotal(o.id()).subtract(o.paidAmount()).subtract(o.pendingAmount())
                .subtract(o.onAccount());
        if (sum.compareTo(due) != 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A soma dos meios (" + sum.toPlainString()
                    + ") precisa ser igual ao saldo devedor (" + due.toPlainString() + ").",
                    List.of(new FieldError("parts", "Soma diferente do saldo devedor")));
        }
        // Permissões por meio, todas antes de gravar qualquer coisa.
        for (Valid v : valid) {
            switch (v.p().method()) {
                case "CASH", "DEBIT", "CREDIT" -> authz.require(actor, "payments", "confirm");
                case "PIX", "BANK_TRANSFER" -> authz.require(actor, "payments", "create");
                case "ON_ACCOUNT" -> authz.requireAdmin(actor, "receivables.create"); // D15 / ADR-017
                default -> throw new IllegalStateException();
            }
        }
        UUID session = valid.stream().anyMatch(v -> v.p().method().equals("CASH")) ? cash.requireOpenSession(actor) : null;
        List<PaymentView> created = new ArrayList<>();
        for (Valid v : valid) {
            UUID id = Ids.newId();
            String method = v.p().method();
            boolean confirmed = !method.equals("PIX") && !method.equals("BANK_TRANSFER");
            BigDecimal change = v.presented() == null ? null : v.presented().subtract(v.amount());
            try {
                jdbc.sql("""
                                INSERT INTO payments (id, organization_id, branch_id, order_id, method, state, confirmation_kind,
                                                      amount, fee, net, presented_cash, change_given, installments,
                                                      provider_reference, document_id, due_date, cash_session_id, notes,
                                                      created_by, confirmed_at, confirmed_by, trip_id)
                                VALUES (:id, :o, :b, :ord, :m, :st, 'MANUAL_AUTHORIZED', :a, :fee, :net, :pc, :ch, :inst,
                                        :ref, :doc, :due, :cs, :notes, :by, CASE WHEN :conf THEN now() END, :cby, :trip)
                                """).param("trip", tripId)
                        .param("id", id).param("o", actor.organizationId()).param("b", o.branchId()).param("ord", o.id())
                        .param("m", method).param("st", confirmed ? "CONFIRMED" : "PENDING").param("a", v.amount())
                        .param("fee", v.fee()).param("net", v.amount().subtract(v.fee())).param("pc", v.presented())
                        .param("ch", change).param("inst", method.equals("CREDIT") || method.equals("DEBIT")
                                ? (v.p().installments() == null ? 1 : v.p().installments()) : null)
                        .param("ref", Text.trim(v.p().provider_reference())).param("doc", v.p().document_id())
                        .param("due", v.due()).param("cs", method.equals("CASH") ? session : null).param("notes", notes)
                        .param("by", actor.userId()).param("conf", confirmed).param("cby", confirmed ? actor.userId() : null)
                        .update();
            } catch (DuplicateKeyException e) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Transação de cartão já registrada (NSU repetido).");
            }
            if (method.equals("CASH")) {
                cash.movement(session, "IN", v.amount(), "payment", id); // só o valor do pedido; troco não entra
            }
            if (confirmed) {
                postConfirmed(actor, o, id, method, v.amount(), v.fee());
            }
            created.add(view(find(id).orElseThrow()));
        }
        orders.refreshPayment(o.id());
        audit.record(entry(actor, "payments.register", o.id(), "order", Map.of("parts", valid.size(),
                "amount", sum.toPlainString(), "methods", valid.stream().map(v -> v.p().method()).toList())));
        return new RegisterResult(created, orders.view(actor, orders.find(o.id()).orElseThrow()));
    }

    /** Lançamento do recebimento confirmado: entra o meio, credita CLIENTES (adiantamento ou baixa do a receber). */
    private void postConfirmed(Actor actor, Row o, UUID paymentId, String method, BigDecimal amount, BigDecimal fee) {
        List<Line> lines = switch (method) {
            case "CASH" -> List.of(Line.debit("CAIXA", amount), Line.credit("CLIENTES", amount));
            case "PIX", "BANK_TRANSFER" -> List.of(Line.debit("BANCO", amount), Line.credit("CLIENTES", amount));
            case "DEBIT", "CREDIT" -> List.of(Line.debit("RECEBIVEL_CARTAO", amount.subtract(fee)),
                    Line.debit("TAXAS_CARTAO", fee), Line.credit("CLIENTES", amount));
            default -> List.of(); // a prazo: não é dinheiro; o a receber nasce na entrega (débito em CLIENTES)
        };
        journal.post(actor, o.branchId(), "payment", paymentId, "PAYMENT_CONFIRMED", "Pedido #" + o.number(), null, lines);
    }

    // ---------------- conferência manual do Pix/transferência ----------------
    @Transactional
    public PaymentView confirm(Actor actor, UUID id, String idemKey, long expectedVersion, NoteCommand cmd) {
        authz.require(actor, "payments", "confirm");
        PRow p = find(id).orElseThrow(ApiException::notFound);
        Row o = orders.lockForPayment(actor, p.orderId());
        String note = cmd == null ? null : Text.trim(cmd.note());
        if (note == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe como conferiu (ex.: extrato do banco).",
                    List.of(new FieldError("note", "Obrigatório")));
        }
        return idem.execute(actor, "payments.confirm", idemKey, Map.of("id", id.toString(), "note", note,
                "version", expectedVersion), PaymentView.class, () -> {
            transition(p, expectedVersion, "PENDING", "CONFIRMED", actor, note);
            postConfirmed(actor, o, p.id(), p.method(), p.amount(), p.fee());
            orders.refreshPayment(o.id());
            audit.record(entry(actor, "payments.confirm", p.id(), "payment", Map.of("method", p.method(),
                    "amount", p.amount().toPlainString())));
            return view(find(id).orElseThrow());
        });
    }

    @Transactional
    public PaymentView reject(Actor actor, UUID id, String idemKey, long expectedVersion, NoteCommand cmd) {
        authz.require(actor, "payments", "confirm");
        PRow p = find(id).orElseThrow(ApiException::notFound);
        Row o = orders.lockForPayment(actor, p.orderId());
        String note = cmd == null ? null : Text.trim(cmd.note());
        if (note == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("note", "Obrigatório")));
        }
        return idem.execute(actor, "payments.reject", idemKey, Map.of("id", id.toString(), "note", note,
                "version", expectedVersion), PaymentView.class, () -> {
            transition(p, expectedVersion, "PENDING", "REJECTED", actor, note);
            orders.refreshPayment(o.id());
            audit.record(entry(actor, "payments.reject", p.id(), "payment", Map.of("note", note)));
            return view(find(id).orElseThrow());
        });
    }

    // ---------------- estorno (devolução de dinheiro / cancelamento de a prazo) ----------------
    @Transactional
    public PaymentView reverse(Actor actor, UUID id, String idemKey, long expectedVersion, NoteCommand cmd) {
        PRow p = find(id).orElseThrow(ApiException::notFound);
        authz.requireAmountWithinLimit(actor, "payments", "reverse", p.amount()); // alçada de estorno
        Row o = orders.lockForPayment(actor, p.orderId());
        String note = cmd == null ? null : Text.trim(cmd.note());
        if (note == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("note", "Obrigatório")));
        }
        return idem.execute(actor, "payments.reverse", idemKey, Map.of("id", id.toString(), "note", note,
                "version", expectedVersion), PaymentView.class, () -> {
            if (!p.state().equals("CONFIRMED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só pagamento confirmado pode ser estornado.");
            }
            int n = jdbc.sql("UPDATE payments SET state = 'REVERSED', reversed_at = now(), reversed_by = :by, "
                            + "decided_note = :n, version = version + 1 WHERE id = :id AND version = :v AND state = 'CONFIRMED'")
                    .param("by", actor.userId()).param("n", note).param("id", id).param("v", expectedVersion).update();
            if (n == 0) {
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "O pagamento foi alterado. Recarregue.");
            }
            if (p.method().equals("CASH")) {
                cash.movement(cash.requireOpenSession(actor), "OUT", p.amount(), "payment_reversal", p.id());
            }
            UUID original = jdbc.sql("SELECT id FROM journal_entries WHERE source_type = 'payment' AND source_id = :s")
                    .param("s", p.id()).query(UUID.class).optional().orElse(null);
            List<Line> lines = switch (p.method()) {
                case "CASH" -> List.of(Line.debit("CLIENTES", p.amount()), Line.credit("CAIXA", p.amount()));
                case "PIX", "BANK_TRANSFER" -> List.of(Line.debit("CLIENTES", p.amount()), Line.credit("BANCO", p.amount()));
                case "DEBIT", "CREDIT" -> List.of(Line.debit("CLIENTES", p.amount()),
                        Line.credit("RECEBIVEL_CARTAO", p.net()), Line.credit("TAXAS_CARTAO", p.fee()));
                default -> List.of();
            };
            journal.post(actor, o.branchId(), "payment", p.id(), "PAYMENT_REVERSED", note, original, lines);
            orders.refreshPayment(o.id());
            audit.record(entry(actor, "payments.reverse", p.id(), "payment", Map.of("method", p.method(),
                    "amount", p.amount().toPlainString(), "note", note)));
            return view(find(id).orElseThrow());
        });
    }

    @Transactional(readOnly = true)
    public List<PaymentView> listForOrder(Actor actor, UUID orderId) {
        authz.require(actor, "payments", "view");
        orders.get(actor, orderId); // escopo do pedido
        return jdbc.sql(SELECT + " WHERE p.order_id = :o ORDER BY p.created_at, p.id").param("o", orderId)
                .query(PRow.class).list().stream().map(this::view).toList();
    }

    private void transition(PRow p, long expectedVersion, String from, String to, Actor actor, String note) {
        if (!p.state().equals(from)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Pagamento está " + p.state() + ".");
        }
        int n = jdbc.sql("""
                        UPDATE payments SET state = :to, confirmed_by = CASE WHEN :to = 'CONFIRMED' THEN :by END,
                               confirmed_at = CASE WHEN :to = 'CONFIRMED' THEN now() END, decided_note = :n,
                               version = version + 1
                         WHERE id = :id AND version = :v AND state = :from
                        """)
                .param("to", to).param("by", actor.userId()).param("n", note).param("id", p.id())
                .param("v", expectedVersion).param("from", from).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O pagamento foi alterado. Recarregue.");
        }
    }

    private static final String SELECT = """
            SELECT p.id, p.organization_id, p.branch_id, p.order_id, p.method, p.state, p.confirmation_kind, p.amount, p.fee,
                   p.net, p.presented_cash, p.change_given, p.installments, p.provider_reference, p.document_id, p.due_date,
                   p.cash_session_id, cu.display_name AS created_by_name, p.created_at, ku.display_name AS confirmed_by_name,
                   p.confirmed_at, p.decided_note, p.version
              FROM payments p JOIN app_users cu ON cu.id = p.created_by LEFT JOIN app_users ku ON ku.id = p.confirmed_by
            """;

    private Optional<PRow> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(PRow.class).optional();
    }

    private PaymentView view(PRow p) {
        return new PaymentView(p.id(), p.orderId(), p.method(), p.state(), p.confirmationKind(),
                Decimals.moneyStr(p.amount()), Decimals.moneyStr(p.fee()), Decimals.moneyStr(p.net()),
                Decimals.moneyStr(p.presentedCash()), Decimals.moneyStr(p.changeGiven()), p.installments(),
                p.providerReference(), p.documentId(), p.dueDate(), p.createdByName(), p.createdAt(),
                p.confirmedByName(), p.confirmedAt(), p.decidedNote(), p.version());
    }

    private static AuditEntry entry(Actor actor, String action, UUID id, String type, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, type, id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
