package br.com.engenheiro.api.orders;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.catalogparties.customers.CustomerService;
import br.com.engenheiro.api.catalogparties.products.ProductService;
import br.com.engenheiro.api.catalogparties.products.ProductService.Resolved;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.finance.Journal.Line;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import br.com.engenheiro.api.inventory.StockLedger;
import br.com.engenheiro.api.inventory.StockLedger.Movement;
import br.com.engenheiro.api.inventory.StockLedger.Source;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Cursor;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pedidos de retirada/entrega (ORD-01..08). Estados operacionais (reserva → pronto → cumprido) separados da situação de
 * pagamento. Receita e CMV são reconhecidos só no cumprimento, por quantidade entregue; pagamento antes é adiantamento.
 * Toda mutação bloqueia a linha do pedido (sem cumprimento ou cancelamento duplo concorrente).
 */
@Service
public class OrderService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final ProductService products;
    private final CustomerService customers;
    private final StockLedger ledger;
    private final Journal journal;

    public OrderService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                        ProductService products, CustomerService customers, StockLedger ledger, Journal journal) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.products = products;
        this.customers = customers;
        this.ledger = ledger;
        this.journal = journal;
    }

    // ---------------- contrato ----------------
    public record ItemInput(UUID product_id, String unit, String quantity, String requested_discount) {
    }

    public record DeliveryInput(String address, String reference, String contact, OffsetDateTime window_start,
                                OffsetDateTime window_end, String agreed_payment) {
    }

    public record CreateCommand(String type, UUID customer_id, List<ItemInput> items, String notes,
                                DeliveryInput delivery) {
    }

    public record LineQty(int line_no, String quantity) {
    }

    public record FulfillCommand(List<LineQty> lines) {
    }

    public record ReturnCommand(List<LineQty> lines, String reason) {
    }

    public record ReasonCommand(String reason) {
    }

    public record LineView(int line_no, UUID product_id, String sku, String product_name, String unit, String factor,
                           String quantity, String base_quantity, String base_unit, String unit_price, String gross,
                           String discount, String net, String fulfilled_quantity, String cancelled_quantity,
                           String returned_quantity, String remaining_quantity) {
    }

    public record DeliveryView(String address, String reference, String contact, OffsetDateTime window_start,
                               OffsetDateTime window_end, String agreed_payment) {
    }

    /** Viagem aberta (ou a última) em que o pedido de entrega está. */
    public record DispatchView(UUID trip_id, long trip_number, String trip_state, String stop_state, String driver_name,
                               String plate) {
    }

    public record OrderView(UUID id, long number, String type, String state, String payment_state, UUID customer_id,
                            String customer_name, String branch, String gross_total, String discount_total,
                            String total, String effective_total, String paid_amount, String pending_amount,
                            String on_account, String balance_due, String notes, String created_by_name,
                            OffsetDateTime created_at, String cancel_reason, long version, List<LineView> lines,
                            DeliveryView delivery, DispatchView dispatch, List<String> masked_fields) {
    }

    public record Page(List<OrderView> items, String next_cursor) {
    }

    // ---------------- linhas internas ----------------
    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, long number, String type,
               String sector, UUID customerId, String customerName, String state, String paymentState,
               BigDecimal grossTotal, BigDecimal discountTotal, BigDecimal total, BigDecimal paidAmount,
               BigDecimal pendingAmount, BigDecimal onAccount, String notes, UUID createdBy, String createdByName,
               OffsetDateTime createdAt, String cancelReason, long version, String deliveryAddress,
               String deliveryReference, String deliveryContact, OffsetDateTime windowStart, OffsetDateTime windowEnd,
               String agreedPayment) {
    }

    record LineRow(UUID id, int lineNo, UUID productId, String sku, String productName, String baseUnit, String unit,
                   BigDecimal factor, BigDecimal quantity, BigDecimal baseQuantity, BigDecimal unitPrice,
                   BigDecimal gross, BigDecimal discount, BigDecimal net, UUID reservationId,
                   BigDecimal fulfilledBaseQty, BigDecimal cancelledBaseQty, BigDecimal returnedBaseQty,
                   BigDecimal recognizedNet) {
        BigDecimal open() {
            return baseQuantity.subtract(fulfilledBaseQty).subtract(cancelledBaseQty);
        }
    }

    static String resource(String type) {
        return "DELIVERY".equals(type) ? "orders.delivery" : "orders.pickup";
    }

    // ---------------- criar ----------------
    @Transactional
    public OrderView create(Actor actor, String idemKey, CreateCommand cmd) {
        String type = cmd.type() == null ? "PICKUP" : cmd.type();
        if (!type.equals("PICKUP") && !type.equals("DELIVERY")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Tipo inválido.",
                    List.of(new FieldError("type", "Use PICKUP (retirada) ou DELIVERY (entrega)")));
        }
        String resource = resource(type);
        authz.require(actor, resource, "create");
        return idem.execute(actor, "orders.create", idemKey, cmd, OrderView.class, () -> doCreate(actor, type, resource, cmd));
    }

    private OrderView doCreate(Actor actor, String type, String resource, CreateCommand cmd) {
        List<FieldError> errors = new ArrayList<>();
        if (cmd.customer_id() == null) {
            errors.add(new FieldError("customer_id", "Selecione o cliente"));
        } else {
            try {
                customers.get(actor, cmd.customer_id()); // escopo do cliente vale para o pedido
            } catch (ApiException e) {
                errors.add(new FieldError("customer_id", "Cliente não encontrado"));
            }
        }
        String notes = Text.trim(cmd.notes());
        if (notes != null && notes.length() > 1000) {
            errors.add(new FieldError("notes", "Até 1000 caracteres"));
        }
        DeliveryInput dlv = type.equals("DELIVERY") ? validateDelivery(actor, cmd.delivery(), errors) : null;
        if (type.equals("PICKUP") && cmd.delivery() != null) {
            errors.add(new FieldError("delivery", "Retirada não tem dados de entrega"));
        }
        List<ItemInput> items = cmd.items() == null ? List.of() : cmd.items();
        if (items.isEmpty() || items.size() > 50) {
            errors.add(new FieldError("items", "Informe de 1 a 50 itens"));
        }
        record Parsed(Resolved p, BigDecimal qty, BigDecimal price, BigDecimal gross, BigDecimal discount) {
        }
        List<Parsed> parsed = new ArrayList<>();
        BigDecimal discountTotal = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            ItemInput it = items.get(i);
            String f = "items[" + i + "]";
            Resolved p = products.resolve(actor, it.product_id(), it.unit(), f, errors);
            BigDecimal qty = Decimals.parse(it.quantity(), Decimals.QTY_SCALE, false, f + ".quantity", errors);
            BigDecimal disc = Decimals.parseOptional(it.requested_discount(), Decimals.MONEY_SCALE,
                    f + ".requested_discount", errors);
            if (p == null || qty == null) {
                continue;
            }
            Optional<BigDecimal> price = products.currentPrice(p.productId(), p.unit());
            if (price.isEmpty()) {
                errors.add(new FieldError(f + ".product_id", "Produto sem preço de venda nesta medida"));
                continue;
            }
            BigDecimal gross = Decimals.money(qty.multiply(price.get()));
            BigDecimal d = disc == null ? BigDecimal.ZERO.setScale(2) : disc;
            if (d.compareTo(gross) > 0) {
                errors.add(new FieldError(f + ".requested_discount", "Desconto maior que o valor do item"));
                continue;
            }
            discountTotal = discountTotal.add(d);
            parsed.add(new Parsed(p, qty, price.get(), gross, d));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        requireDiscountWithinLimit(actor, resource, discountTotal);

        long number = jdbc.sql("""
                        INSERT INTO order_counters (branch_id, last_no) VALUES (:b, 1)
                        ON CONFLICT (branch_id) DO UPDATE SET last_no = order_counters.last_no + 1
                        RETURNING last_no
                        """).param("b", actor.branchId()).query(Long.class).single();
        UUID id = Ids.newId();
        BigDecimal gross = parsed.stream().map(Parsed::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.sql("""
                        INSERT INTO orders (id, organization_id, branch_id, number, type, sector, customer_id, state,
                                            gross_total, discount_total, total, notes, created_by, delivery_address,
                                            delivery_reference, delivery_contact, window_start, window_end,
                                            agreed_payment)
                        VALUES (:id, :o, :b, :n, :t, :s, :c, 'CONFIRMED', :g, :d, :tot, :notes, :by, :da, :dr, :dc,
                                :ws, :we, :ap)
                        """)
                .param("da", dlv == null ? null : dlv.address()).param("dr", dlv == null ? null : dlv.reference())
                .param("dc", dlv == null ? null : dlv.contact()).param("ws", dlv == null ? null : dlv.window_start())
                .param("we", dlv == null ? null : dlv.window_end()).param("ap", dlv == null ? null : dlv.agreed_payment())
                .param("id", id).param("o", actor.organizationId()).param("b", actor.branchId()).param("n", number)
                .param("t", type).param("s", type.equals("PICKUP") ? Sector.RETIRADA.name() : Sector.ENTREGAS.name())
                .param("c", cmd.customer_id()).param("g", gross).param("d", discountTotal)
                .param("tot", gross.subtract(discountTotal)).param("notes", notes).param("by", actor.userId()).update();
        record Inserted(UUID lineId, Parsed p) {
        }
        List<Inserted> inserted = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            Parsed p = parsed.get(i);
            UUID lineId = Ids.newId();
            jdbc.sql("""
                            INSERT INTO order_lines (id, order_id, line_no, product_id, unit, factor, quantity,
                                                     base_quantity, unit_price, gross, discount, net)
                            VALUES (:id, :o, :n, :p, :u, :f, :q, :bq, :price, :g, :d, :net)
                            """)
                    .param("id", lineId).param("o", id).param("n", i + 1).param("p", p.p().productId())
                    .param("u", p.p().unit()).param("f", p.p().factor()).param("q", p.qty())
                    .param("bq", p.qty().multiply(p.p().factor())).param("price", p.price()).param("g", p.gross())
                    .param("d", p.discount()).param("net", p.gross().subtract(p.discount())).update();
            inserted.add(new Inserted(lineId, p));
        }
        // Reserva atômica, em ordem fixa de produto (sem impasse entre pedidos simultâneos).
        inserted.sort(Comparator.comparing((Inserted x) -> x.p().p().productId()));
        for (Inserted x : inserted) {
            UUID res = ledger.reserve(actor, actor.branchId(), x.p().p().productId(), x.p().qty().multiply(x.p().p().factor()),
                    "order_line", x.lineId(), null);
            jdbc.sql("UPDATE order_lines SET reservation_id = :r WHERE id = :id").param("r", res)
                    .param("id", x.lineId()).update();
        }
        audit.record(entry(actor, "orders.create", id, Map.of("number", number, "type", type, "lines", parsed.size(),
                "total", gross.subtract(discountTotal).toPlainString(), "discount", discountTotal.toPlainString())));
        return view(actor, find(id).orElseThrow());
    }

    /**
     * Pedido de entrega (LOG-01): endereço e forma de pagamento combinada obrigatórios; janela coerente. Quem não vê
     * endereço/contato (política de campo) não pode gravá-los — usa o cadastro do cliente, se houver.
     */
    private DeliveryInput validateDelivery(Actor actor, DeliveryInput d, List<FieldError> errors) {
        if (d == null) {
            errors.add(new FieldError("delivery", "Informe endereço e pagamento combinado da entrega"));
            return null;
        }
        boolean seesAddress = authz.canSeeField(actor, br.com.engenheiro.api.identityaccess.domain.FieldClass.DELIVERY_ADDRESS);
        boolean seesContact = authz.canSeeField(actor, br.com.engenheiro.api.identityaccess.domain.FieldClass.OPERATIONAL_CONTACT);
        String address = Text.trim(d.address());
        String reference = Text.trim(d.reference());
        String contact = Text.trim(d.contact());
        if (!seesAddress && (address != null || reference != null)) {
            errors.add(new FieldError("delivery.address", "Sem permissão para informar endereço"));
        }
        if (!seesContact && contact != null) {
            errors.add(new FieldError("delivery.contact", "Sem permissão para informar contato"));
        }
        if (address == null) {
            errors.add(new FieldError("delivery.address", "Endereço de entrega obrigatório"));
        } else if (address.length() > 300) {
            errors.add(new FieldError("delivery.address", "Até 300 caracteres"));
        }
        if (reference != null && reference.length() > 200) {
            errors.add(new FieldError("delivery.reference", "Até 200 caracteres"));
        }
        if (contact != null && contact.length() > 100) {
            errors.add(new FieldError("delivery.contact", "Até 100 caracteres"));
        }
        if ((d.window_start() == null) != (d.window_end() == null)) {
            errors.add(new FieldError("delivery.window_end", "Informe início e fim da janela"));
        } else if (d.window_start() != null && !d.window_end().isAfter(d.window_start())) {
            errors.add(new FieldError("delivery.window_end", "Fim da janela deve ser depois do início"));
        }
        if (d.agreed_payment() == null || !PaymentService.METHODS.contains(d.agreed_payment())) {
            errors.add(new FieldError("delivery.agreed_payment", "Escolha a forma de pagamento combinada"));
        }
        return new DeliveryInput(address, reference, contact, d.window_start(), d.window_end(), d.agreed_payment());
    }

    /**
     * Alçada de desconto (ADR-018): administrador sem limite; demais só com concessão `edit_draft` do tipo de pedido
     * que tenha valor máximo explícito. Concessão sem valor não autoriza desconto.
     */
    private void requireDiscountWithinLimit(Actor actor, String resource, BigDecimal discount) {
        if (discount.signum() == 0 || actor.admin()) {
            return;
        }
        Decision d = authz.decide(actor, resource, "edit_draft"); // respeita negação explícita
        java.time.Instant now = java.time.Instant.now();
        BigDecimal limit = actor.grants().stream()
                .filter(g -> g.effect() == br.com.engenheiro.api.identityaccess.domain.Effect.ALLOW
                        && g.matches(resource, "edit_draft") && g.isActiveAt(now) && g.maxAmount() != null)
                .map(br.com.engenheiro.api.identityaccess.domain.Grant::maxAmount)
                .max(BigDecimal::compareTo).orElse(null);
        if (!d.allowed() || limit == null || discount.compareTo(limit) > 0) {
            audit.recordIndependently(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                    actor.clientId(), "access.denied", "order", null, "DENIED", "desconto acima da alçada",
                    Map.of("capability", resource + ".edit_draft", "discount", discount.toPlainString()), "api"));
            throw new ApiException(ErrorCode.FORBIDDEN, "Desconto acima da sua alçada. Peça a quem tem alçada.");
        }
    }

    // ---------------- pronto para retirada ----------------
    @Transactional
    public OrderView markReady(Actor actor, UUID id, long expectedVersion) {
        Row o = lockRow(actor, id, "confirm");
        if (!o.state().equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só pedido confirmado pode ser marcado como pronto.");
        }
        bump(o, expectedVersion);
        jdbc.sql("UPDATE orders SET state = 'READY' WHERE id = :id").param("id", id).update();
        audit.record(entry(actor, "orders.ready", id, Map.of("number", o.number())));
        return view(actor, find(id).orElseThrow());
    }

    // ---------------- cumprimento (retirada/entrega) ----------------
    @Transactional
    public OrderView fulfill(Actor actor, UUID id, String idemKey, FulfillCommand cmd) {
        Row o = lockRow(actor, id, "confirm");
        Map<String, Object> req = new HashMap<>();
        req.put("id", id.toString());
        req.put("body", cmd);
        return idem.execute(actor, "orders.fulfill", idemKey, req, OrderView.class, () -> doFulfill(actor, o, cmd));
    }

    private OrderView doFulfill(Actor actor, Row o, FulfillCommand cmd) {
        if (!Set.of("CONFIRMED", "READY", "PARTIALLY_FULFILLED").contains(o.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Pedido " + o.state() + " não pode ser cumprido.");
        }
        List<LineRow> lines = lines(o.id());
        Map<Integer, BigDecimal> wanted = new HashMap<>();
        List<FieldError> errors = new ArrayList<>();
        if (cmd == null || cmd.lines() == null || cmd.lines().isEmpty()) {
            for (LineRow l : lines) {
                if (l.open().signum() > 0) {
                    wanted.put(l.lineNo(), l.open()); // tudo o que falta
                }
            }
        } else {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < cmd.lines().size(); i++) {
                LineQty lq = cmd.lines().get(i);
                LineRow l = lines.stream().filter(x -> x.lineNo() == lq.line_no()).findFirst().orElse(null);
                if (l == null || !seen.add(lq.line_no())) {
                    errors.add(new FieldError("lines[" + i + "].line_no", "Linha inexistente ou repetida"));
                    continue;
                }
                BigDecimal q = Decimals.parse(lq.quantity(), Decimals.QTY_SCALE, false, "lines[" + i + "].quantity", errors);
                if (q == null) {
                    continue;
                }
                BigDecimal base = q.multiply(l.factor());
                if (base.compareTo(l.open()) > 0) {
                    errors.add(new FieldError("lines[" + i + "].quantity", "Maior que o pendente da linha"));
                    continue;
                }
                wanted.put(l.lineNo(), base);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija as quantidades.", errors);
        }
        if (wanted.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Nada pendente para cumprir.");
        }
        // Valor que será reconhecido e cobertura financeira (confirmado + a prazo autorizado; Pix pendente não conta).
        Map<Integer, BigDecimal> share = new HashMap<>();
        BigDecimal recognizedAfter = lines.stream().map(LineRow::recognizedNet).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (LineRow l : lines) {
            BigDecimal q = wanted.get(l.lineNo());
            if (q == null) {
                continue;
            }
            boolean completes = l.fulfilledBaseQty().add(q).compareTo(l.baseQuantity()) == 0;
            BigDecimal s = completes ? l.net().subtract(l.recognizedNet())
                    : l.net().multiply(q).divide(l.baseQuantity(), 2, RoundingMode.HALF_UP);
            share.put(l.lineNo(), s);
            recognizedAfter = recognizedAfter.add(s);
        }
        BigDecimal returnedNet = returnedNet(o.id());
        BigDecimal covered = o.paidAmount().add(o.onAccount());
        if (recognizedAfter.subtract(returnedNet).compareTo(covered) > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Pagamento pendente: entregue só o que está pago ou a prazo "
                    + "autorizado (coberto " + covered.toPlainString() + ", necessário "
                    + recognizedAfter.subtract(returnedNet).toPlainString() + ").");
        }
        UUID fid = Ids.newId();
        jdbc.sql("INSERT INTO fulfillments (id, order_id, kind, actor_id) VALUES (:id, :o, 'FULFILLMENT', :a)")
                .param("id", fid).param("o", o.id()).param("a", actor.userId()).update();
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        List<LineRow> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparing(LineRow::productId).thenComparing(LineRow::lineNo));
        for (LineRow l : ordered) {
            BigDecimal q = wanted.get(l.lineNo());
            if (q == null) {
                continue;
            }
            Movement m = ledger.consumeReservation(actor, l.reservationId(), q, new Source("order_fulfillment", fid, l.lineNo()));
            BigDecimal net = share.get(l.lineNo());
            boolean completes = l.fulfilledBaseQty().add(q).compareTo(l.baseQuantity()) == 0;
            BigDecimal recognizedGross = jdbc.sql("""
                            SELECT coalesce(sum(round(ol.gross * fl.base_quantity / ol.base_quantity, 2)), 0)
                              FROM fulfillment_lines fl JOIN fulfillments f ON f.id = fl.fulfillment_id
                              JOIN order_lines ol ON ol.id = fl.order_line_id
                             WHERE fl.order_line_id = :l AND f.kind = 'FULFILLMENT'
                            """).param("l", l.id()).query(BigDecimal.class).single();
            BigDecimal gross = completes ? l.gross().subtract(recognizedGross)
                    : l.gross().multiply(q).divide(l.baseQuantity(), 2, RoundingMode.HALF_UP);
            BigDecimal cost = m.totalCost().negate();
            jdbc.sql("""
                            INSERT INTO fulfillment_lines (id, fulfillment_id, order_line_id, base_quantity, net_revenue,
                                                           cost, movement_id)
                            VALUES (:id, :f, :l, :q, :net, :c, :m)
                            """)
                    .param("id", Ids.newId()).param("f", fid).param("l", l.id()).param("q", q).param("net", net)
                    .param("c", cost).param("m", m.id()).update();
            jdbc.sql("UPDATE order_lines SET fulfilled_base_qty = fulfilled_base_qty + :q, "
                            + "recognized_net = recognized_net + :net WHERE id = :id")
                    .param("q", q).param("net", net).param("id", l.id()).update();
            netTotal = netTotal.add(net);
            grossTotal = grossTotal.add(gross);
            costTotal = costTotal.add(cost);
        }
        BigDecimal cmv = Decimals.money(costTotal);
        journal.post(actor, o.branchId(), "order_fulfillment", fid, "SALE_RECOGNIZED", "Pedido #" + o.number(), null,
                List.of(Line.debit("CLIENTES", netTotal), Line.debit("DESCONTOS_CONCEDIDOS", grossTotal.subtract(netTotal)),
                        Line.credit("RECEITA_VENDAS", grossTotal), Line.debit("CMV", cmv), Line.credit("ESTOQUE", cmv)));
        refreshState(o.id());
        audit.record(entry(actor, "orders.fulfill", o.id(), Map.of("fulfillment_id", fid.toString(),
                "lines", wanted.size(), "net", netTotal.toPlainString())));
        return view(actor, find(o.id()).orElseThrow());
    }

    /**
     * Cumprimento de entrega feito pela viagem (LOG-03): o chamador (TripService) já autorizou por `trips.confirm` no
     * escopo da viagem; aqui só se bloqueia o pedido e se aplicam as mesmas regras de cobertura e reconhecimento.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OrderView fulfillForTrip(Actor actor, UUID orderId, FulfillCommand cmd) {
        Row o = jdbc.sql(SELECT + " WHERE o.id = :id FOR UPDATE OF o").param("id", orderId).query(Row.class).single();
        if (!o.type().equals("DELIVERY")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só pedido de entrega é cumprido pela viagem.");
        }
        return doFulfill(actor, o, cmd);
    }

    /** Pedido bloqueado para recebimento na viagem (autorizado pelo chamador). */
    Row lockForTrip(UUID orderId) {
        return jdbc.sql(SELECT + " WHERE o.id = :id FOR UPDATE OF o").param("id", orderId).query(Row.class).single();
    }

    /** Situação resumida para a parada da viagem (sem custo). */
    public record StopOrder(UUID id, long number, String customer_name, String state, String payment_state,
                            String effective_total, String paid_amount, String pending_amount, String on_account,
                            String balance_due, List<LineView> lines, DeliveryView delivery, List<String> masked_fields) {
    }

    @Transactional(readOnly = true)
    public StopOrder stopOrder(Actor actor, UUID orderId) {
        OrderView v = view(actor, find(orderId).orElseThrow());
        return new StopOrder(v.id(), v.number(), v.customer_name(), v.state(), v.payment_state(), v.effective_total(),
                v.paid_amount(), v.pending_amount(), v.on_account(), v.balance_due(), v.lines(), v.delivery(),
                v.masked_fields());
    }

    /** Linhas abertas (quantidade-base) por produto, para capacidade. */
    public record OpenLine(UUID productId, String productName, String baseUnit, BigDecimal openBase,
                           BigDecimal fulfilledBase) {
    }

    public List<OpenLine> openLines(UUID orderId) {
        return lines(orderId).stream().map(l -> new OpenLine(l.productId(), l.productName(), l.baseUnit(), l.open(),
                l.fulfilledBaseQty())).toList();
    }

    public record OrderHead(UUID id, UUID organizationId, UUID branchId, long number, String type, String state,
                            String sector, UUID createdBy) {
    }

    public Optional<OrderHead> head(UUID orderId) {
        return find(orderId).map(o -> new OrderHead(o.id(), o.organizationId(), o.branchId(), o.number(), o.type(),
                o.state(), o.sector(), o.createdBy()));
    }

    // ---------------- cancelamento ----------------
    @Transactional
    public OrderView cancel(Actor actor, UUID id, String idemKey, long expectedVersion, ReasonCommand cmd) {
        Row o = lockRow(actor, id, "cancel");
        String reason = requireReason(cmd == null ? null : cmd.reason());
        return idem.execute(actor, "orders.cancel", idemKey, Map.of("id", id.toString(), "reason", reason,
                "version", expectedVersion), OrderView.class, () -> {
            if (Set.of("FULFILLED", "CANCELLED").contains(o.state())) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION,
                        "Pedido já encerrado; venda cumprida exige devolução formal.");
            }
            bump(o, expectedVersion);
            // Pedido em rota não é cancelado pelo escritório: a viagem registra ocorrência e retorno (LOG-04).
            String tripState = jdbc.sql("""
                            SELECT t.state FROM trip_stops s JOIN trips t ON t.id = s.trip_id
                             WHERE s.order_id = :o AND s.is_open
                            """).param("o", o.id()).query(String.class).optional().orElse(null);
            if ("DEPARTED".equals(tripState)) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION,
                        "Pedido está em rota; registre a ocorrência na viagem e cancele após o retorno.");
            }
            jdbc.sql("UPDATE trip_stops SET state = 'REMOVED', is_open = false, version = version + 1 "
                    + "WHERE order_id = :o AND is_open").param("o", o.id()).update();
            boolean anyFulfilled = false;
            for (LineRow l : lines(o.id())) {
                if (l.fulfilledBaseQty().signum() > 0) {
                    anyFulfilled = true;
                }
                if (l.open().signum() > 0) {
                    ledger.release(l.reservationId(), "RELEASED");
                    jdbc.sql("UPDATE order_lines SET cancelled_base_qty = cancelled_base_qty + :q WHERE id = :id")
                            .param("q", l.open()).param("id", l.id()).update();
                }
            }
            // Com entrega parcial, o saldo é cancelado e o pedido fica encerrado como cumprido no que foi entregue.
            jdbc.sql("UPDATE orders SET state = :s, cancelled_at = now(), cancelled_by = :by, cancel_reason = :r WHERE id = :id")
                    .param("s", anyFulfilled ? "FULFILLED" : "CANCELLED").param("by", actor.userId()).param("r", reason)
                    .param("id", o.id()).update();
            refreshPayment(o.id());
            audit.record(entry(actor, "orders.cancel", o.id(), Map.of("reason", reason, "remainder_only", anyFulfilled)));
            return view(actor, find(o.id()).orElseThrow());
        });
    }

    // ---------------- devolução de venda cumprida ----------------
    @Transactional
    public OrderView returnItems(Actor actor, UUID id, String idemKey, ReturnCommand cmd) {
        Row o = lockRow(actor, id, "reverse");
        Map<String, Object> req = new HashMap<>();
        req.put("id", id.toString());
        req.put("body", cmd);
        return idem.execute(actor, "orders.return", idemKey, req, OrderView.class, () -> {
            String reason = requireReason(cmd == null ? null : cmd.reason());
            List<LineRow> lines = lines(o.id());
            List<FieldError> errors = new ArrayList<>();
            Map<LineRow, BigDecimal> wanted = new HashMap<>();
            List<LineQty> in = cmd.lines() == null ? List.of() : cmd.lines();
            for (int i = 0; i < in.size(); i++) {
                LineQty lq = in.get(i);
                LineRow l = lines.stream().filter(x -> x.lineNo() == lq.line_no()).findFirst().orElse(null);
                BigDecimal q = Decimals.parse(lq.quantity(), Decimals.QTY_SCALE, false, "lines[" + i + "].quantity", errors);
                if (l == null || q == null) {
                    if (l == null) {
                        errors.add(new FieldError("lines[" + i + "].line_no", "Linha inexistente"));
                    }
                    continue;
                }
                BigDecimal base = q.multiply(l.factor());
                if (base.compareTo(l.fulfilledBaseQty().subtract(l.returnedBaseQty())) > 0) {
                    errors.add(new FieldError("lines[" + i + "].quantity", "Maior que o entregue e não devolvido"));
                    continue;
                }
                wanted.put(l, base);
            }
            if (in.isEmpty()) {
                errors.add(new FieldError("lines", "Informe o que volta"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija as quantidades.", errors);
            }
            UUID rid = Ids.newId();
            jdbc.sql("INSERT INTO fulfillments (id, order_id, kind, reason, actor_id) VALUES (:id, :o, 'RETURN', :r, :a)")
                    .param("id", rid).param("o", o.id()).param("r", reason).param("a", actor.userId()).update();
            BigDecimal netTotal = BigDecimal.ZERO;
            BigDecimal costTotal = BigDecimal.ZERO;
            for (Map.Entry<LineRow, BigDecimal> e : wanted.entrySet()) {
                LineRow l = e.getKey();
                BigDecimal q = e.getValue();
                // Custo histórico das saídas desta linha (não o médio de hoje).
                record Hist(BigDecimal qty, BigDecimal cost, UUID firstMovement) {
                }
                Hist h = jdbc.sql("""
                                SELECT sum(fl.base_quantity) AS qty, sum(fl.cost) AS cost,
                                       (array_agg(fl.movement_id ORDER BY f.occurred_at))[1] AS first_movement
                                  FROM fulfillment_lines fl JOIN fulfillments f ON f.id = fl.fulfillment_id
                                 WHERE fl.order_line_id = :l AND f.kind = 'FULFILLMENT'
                                """).param("l", l.id()).query(Hist.class).single();
                BigDecimal cost = h.cost().multiply(q).divide(h.qty(), StockLedger.COST_SCALE_PUBLIC, RoundingMode.HALF_UP);
                Movement m = ledger.receive(actor, o.branchId(), l.productId(), q, cost, "SALE_RETURN",
                        new Source("order_return", rid, l.lineNo()), h.firstMovement(), reason);
                BigDecimal net = l.net().multiply(q).divide(l.baseQuantity(), 2, RoundingMode.HALF_UP);
                jdbc.sql("""
                                INSERT INTO fulfillment_lines (id, fulfillment_id, order_line_id, base_quantity, net_revenue,
                                                               cost, movement_id)
                                VALUES (:id, :f, :l, :q, :net, :c, :m)
                                """)
                        .param("id", Ids.newId()).param("f", rid).param("l", l.id()).param("q", q)
                        .param("net", net.negate()).param("c", cost.negate()).param("m", m.id()).update();
                jdbc.sql("UPDATE order_lines SET returned_base_qty = returned_base_qty + :q WHERE id = :id")
                        .param("q", q).param("id", l.id()).update();
                netTotal = netTotal.add(net);
                costTotal = costTotal.add(cost);
            }
            BigDecimal cmv = Decimals.money(costTotal);
            UUID original = jdbc.sql("""
                            SELECT je.id FROM journal_entries je JOIN fulfillments f ON f.id = je.source_id
                             WHERE f.order_id = :o AND je.event_kind = 'SALE_RECOGNIZED' ORDER BY je.created_at LIMIT 1
                            """).param("o", o.id()).query(UUID.class).optional().orElse(null);
            journal.post(actor, o.branchId(), "order_return", rid, "SALE_RETURNED", reason, original,
                    List.of(Line.debit("DEVOLUCOES_VENDAS", netTotal), Line.credit("CLIENTES", netTotal),
                            Line.debit("ESTOQUE", cmv), Line.credit("CMV", cmv)));
            refreshPayment(o.id());
            audit.record(entry(actor, "orders.return", o.id(), Map.of("return_id", rid.toString(),
                    "net", netTotal.toPlainString(), "reason", reason)));
            return view(actor, find(o.id()).orElseThrow());
        });
    }

    // ---------------- consultas ----------------
    @Transactional(readOnly = true)
    public OrderView get(Actor actor, UUID id) {
        Row o = find(id).orElse(null);
        if (o == null) {
            authz.require(actor, "orders.pickup", "view");
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, resource(o.type()), "view", "order", id.toString(), ref(o));
        return view(actor, o);
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String type, String state, int limit, String cursor) {
        return list(actor, type, state, null, null, null, limit, cursor);
    }

    public Page list(Actor actor, String type, String state, String query, int limit, String cursor) {
        return list(actor, type, state, query, null, null, limit, cursor);
    }

    /**
     * Lista com busca opcional por número do pedido (exato) ou nome do cliente (contém). Só campos que o próprio
     * pedido já mostra a quem o vê: endereço, contato e documento nunca entram na busca.
     */
    @Transactional(readOnly = true)
    public Page list(Actor actor, String type, String state, String query, java.time.LocalDate from,
                     java.time.LocalDate to, int limit, String cursor) {
        Cursor.checkLimit(limit);
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Período inválido: fim exclusivo depois do início.");
        }
        // Data do cadastro em dia civil de São Paulo, [início, fim).
        java.time.ZoneId sp = java.time.ZoneId.of("America/Sao_Paulo");
        OffsetDateTime fromTs = from == null ? null : from.atStartOfDay(sp).toOffsetDateTime();
        OffsetDateTime toTs = to == null ? null : to.atStartOfDay(sp).toOffsetDateTime();
        String q = br.com.engenheiro.api.shared.web.SearchText.normalize(query);
        Long number = q != null && q.replaceFirst("^#", "").matches("\\d{1,12}") ? Long.valueOf(q.replaceFirst("^#", "")) : null;
        List<String> parts = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        for (String t : List.of("PICKUP", "DELIVERY")) {
            if (type != null && !type.equals(t)) {
                continue;
            }
            Decision d = authz.decide(actor, resource(t), "view");
            if (d.allowed()) {
                ScopeSql.Predicate p = ScopeSql.of(actor, d.scopes(),
                        new ScopeSql.Columns("o.organization_id", "o.branch_id", "o.sector", "o.created_by", null));
                parts.add("(o.type = '" + t + "' AND " + p.sql() + ")");
                params.putAll(p.params());
            }
        }
        if (parts.isEmpty()) {
            authz.require(actor, type == null || type.equals("PICKUP") ? "orders.pickup" : "orders.delivery", "view");
        }
        if (state != null && !Set.of("CONFIRMED", "READY", "PARTIALLY_FULFILLED", "FULFILLED", "CANCELLED").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql(SELECT + " WHERE (" + String.join(" OR ", parts) + ")"
                        + " AND (CAST(:st AS text) IS NULL OR o.state = CAST(:st AS text))"
                        + " AND (CAST(:q AS text) IS NULL OR o.number = CAST(:qn AS bigint)"
                        + "      OR c.name ILIKE '%' || CAST(:q AS text) || '%' ESCAPE '\\')"
                        + " AND (CAST(:fts AS timestamptz) IS NULL OR o.created_at >= CAST(:fts AS timestamptz))"
                        + " AND (CAST(:tts AS timestamptz) IS NULL OR o.created_at < CAST(:tts AS timestamptz))"
                        + " AND (CAST(:cts AS text) IS NULL OR (o.created_at, o.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))"
                        + " ORDER BY o.created_at DESC, o.id DESC LIMIT :lim")
                .param("st", state).param("q", br.com.engenheiro.api.shared.web.SearchText.like(q)).param("qn", number).param("fts", fromTs).param("tts", toTs).param("cts", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1);
        for (Map.Entry<String, Object> p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<Row> rows = spec.query(Row.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().createdAt().toString(), rows.getLast().id());
        }
        return new Page(rows.stream().map(r -> view(actor, r)).toList(), next);
    }

    public record HistoryItem(OffsetDateTime occurred_at, String action, String actor_name, String client_id,
                              String entity_type) {
    }

    /** Etapas do pedido e dos pagamentos dele, com autor e app de origem; visível a quem vê o pedido. */
    @Transactional(readOnly = true)
    public List<HistoryItem> history(Actor actor, UUID id) {
        get(actor, id); // autoriza pelo pedido
        return jdbc.sql("""
                        SELECT a.occurred_at, a.action, u.display_name AS actor_name, a.client_id, a.entity_type
                          FROM audit_events a LEFT JOIN app_users u ON u.id = a.actor_user_id
                         WHERE a.organization_id = :org AND a.result = 'SUCCESS'
                           AND (a.entity_id = :oid OR a.entity_id IN (SELECT id::text FROM payments WHERE order_id = :o))
                         ORDER BY a.occurred_at, a.id
                        """).param("org", actor.organizationId()).param("oid", id.toString()).param("o", id)
                .query(HistoryItem.class).list();
    }

    // ---------------- apoio (também usado por pagamentos) ----------------
    /** Bloqueia o pedido e verifica capacidade + escopo do tipo do pedido. */
    Row lockRow(Actor actor, UUID id, String action) {
        Row o = jdbc.sql(SELECT + " WHERE o.id = :id FOR UPDATE OF o").param("id", id).query(Row.class).optional()
                .orElse(null);
        if (o == null) {
            authz.require(actor, "orders.pickup", action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, resource(o.type()), action, "order", id.toString(), ref(o));
        return o;
    }

    /** Bloqueia o pedido sem checar capacidade de pedido (o chamador já autorizou por outro recurso). */
    Row lockForPayment(Actor actor, UUID id) {
        Row o = jdbc.sql(SELECT + " WHERE o.id = :id FOR UPDATE OF o").param("id", id).query(Row.class).optional()
                .orElse(null);
        if (o == null) {
            throw ApiException.notFound();
        }
        // O pedido precisa estar visível para quem recebe (escopo de visualização do tipo do pedido).
        authz.requireRecord(actor, resource(o.type()), "view", "order", id.toString(), ref(o));
        return o;
    }

    /** Trava do pedido para efeitos vindos de aviso assinado do provedor (sem pessoa; a autorização é a assinatura). */
    Row lockForSystem(UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id FOR UPDATE OF o").param("id", id).query(Row.class).optional()
                .orElseThrow(ApiException::notFound);
    }

    /** Valor efetivo do pedido: itens não cancelados nem devolvidos, pelo líquido proporcional. */
    BigDecimal effectiveTotal(UUID orderId) {
        return lines(orderId).stream().map(l -> l.net().subtract(
                        l.net().multiply(l.cancelledBaseQty().add(l.returnedBaseQty()))
                                .divide(l.baseQuantity(), 2, RoundingMode.HALF_UP)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Recalcula pago/pendente/a prazo e a situação de pagamento a partir dos pagamentos. */
    void refreshPayment(UUID orderId) {
        record Sums(BigDecimal paid, BigDecimal pending, BigDecimal onAccount) {
        }
        Sums s = jdbc.sql("""
                        SELECT coalesce(sum(amount) FILTER (WHERE state = 'CONFIRMED' AND method <> 'ON_ACCOUNT'), 0) AS paid,
                               coalesce(sum(amount) FILTER (WHERE state = 'PENDING'), 0) AS pending,
                               coalesce(sum(amount) FILTER (WHERE state = 'CONFIRMED' AND method = 'ON_ACCOUNT'), 0) AS on_account
                          FROM payments WHERE order_id = :o
                        """).param("o", orderId).query(Sums.class).single();
        BigDecimal effective = effectiveTotal(orderId);
        BigDecimal covered = s.paid().add(s.onAccount());
        String st;
        if (covered.compareTo(effective) > 0) {
            st = "REFUND_DUE";
        } else if (covered.signum() == 0) {
            st = "UNPAID";
        } else if (covered.compareTo(effective) < 0) {
            st = "PARTIALLY_PAID";
        } else {
            st = s.onAccount().signum() > 0 ? "ON_ACCOUNT" : "PAID";
        }
        jdbc.sql("UPDATE orders SET paid_amount = :p, pending_amount = :pe, on_account = :oa, payment_state = :st, "
                        + "version = version + 1 WHERE id = :id")
                .param("p", s.paid()).param("pe", s.pending()).param("oa", s.onAccount()).param("st", st)
                .param("id", orderId).update();
    }

    private void refreshState(UUID orderId) {
        List<LineRow> lines = lines(orderId);
        boolean allDone = lines.stream().allMatch(l -> l.open().signum() == 0);
        jdbc.sql("UPDATE orders SET state = :s WHERE id = :id")
                .param("s", allDone ? "FULFILLED" : "PARTIALLY_FULFILLED").param("id", orderId).update();
        refreshPayment(orderId);
    }

    private BigDecimal returnedNet(UUID orderId) {
        return jdbc.sql("""
                        SELECT coalesce(-sum(fl.net_revenue), 0) FROM fulfillment_lines fl
                          JOIN fulfillments f ON f.id = fl.fulfillment_id WHERE f.order_id = :o AND f.kind = 'RETURN'
                        """).param("o", orderId).query(BigDecimal.class).single();
    }

    private void bump(Row o, long expectedVersion) {
        int n = jdbc.sql("UPDATE orders SET version = version + 1 WHERE id = :id AND version = :v")
                .param("id", o.id()).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O pedido foi alterado por outra pessoa. Recarregue.");
        }
    }

    private static String requireReason(String r) {
        String t = Text.trim(r);
        if (t == null || t.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório (até 500 caracteres)")));
        }
        return t;
    }

    static RecordRef ref(Row o) {
        return new RecordRef(o.organizationId(), o.branchId(), Sector.valueOf(o.sector()), o.createdBy(), null);
    }

    static final String SELECT = """
            SELECT o.id, o.organization_id, o.branch_id, b.name AS branch_name, o.number, o.type, o.sector, o.customer_id,
                   c.name AS customer_name, o.state, o.payment_state, o.gross_total, o.discount_total, o.total,
                   o.paid_amount, o.pending_amount, o.on_account, o.notes, o.created_by, u.display_name AS created_by_name,
                   o.created_at, o.cancel_reason, o.version, o.delivery_address, o.delivery_reference,
                   o.delivery_contact, o.window_start, o.window_end, o.agreed_payment
              FROM orders o JOIN branches b ON b.id = o.branch_id JOIN customers c ON c.id = o.customer_id
              JOIN app_users u ON u.id = o.created_by
            """;

    Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id).query(Row.class).optional();
    }

    List<LineRow> lines(UUID orderId) {
        return jdbc.sql("""
                        SELECT l.id, l.line_no, l.product_id, p.sku, p.name AS product_name, p.base_unit, l.unit, l.factor,
                               l.quantity, l.base_quantity, l.unit_price, l.gross, l.discount, l.net, l.reservation_id,
                               l.fulfilled_base_qty, l.cancelled_base_qty, l.returned_base_qty, l.recognized_net
                          FROM order_lines l JOIN products p ON p.id = l.product_id
                         WHERE l.order_id = :o ORDER BY l.line_no
                        """).param("o", orderId).query(LineRow.class).list();
    }

    OrderView view(Actor actor, Row o) {
        BigDecimal effective = effectiveTotal(o.id());
        BigDecimal balance = effective.subtract(o.paidAmount()).subtract(o.onAccount());
        List<String> masked = new ArrayList<>();
        List<LineView> lv = lines(o.id()).stream().map(l -> new LineView(l.lineNo(), l.productId(), l.sku(),
                l.productName(), l.unit(), Decimals.str(l.factor()), Decimals.str(l.quantity()),
                Decimals.str(l.baseQuantity()), l.baseUnit(), Decimals.moneyStr(l.unitPrice()), Decimals.moneyStr(l.gross()),
                Decimals.moneyStr(l.discount()), Decimals.moneyStr(l.net()),
                Decimals.str(l.fulfilledBaseQty().divide(l.factor(), 6, RoundingMode.HALF_UP)),
                Decimals.str(l.cancelledBaseQty().divide(l.factor(), 6, RoundingMode.HALF_UP)),
                Decimals.str(l.returnedBaseQty().divide(l.factor(), 6, RoundingMode.HALF_UP)),
                Decimals.str(l.open().divide(l.factor(), 6, RoundingMode.HALF_UP)))).toList();
        return new OrderView(o.id(), o.number(), o.type(), o.state(), o.paymentState(), o.customerId(), o.customerName(),
                o.branchName(), Decimals.moneyStr(o.grossTotal()), Decimals.moneyStr(o.discountTotal()),
                Decimals.moneyStr(o.total()), Decimals.moneyStr(effective), Decimals.moneyStr(o.paidAmount()),
                Decimals.moneyStr(o.pendingAmount()), Decimals.moneyStr(o.onAccount()), Decimals.moneyStr(balance),
                o.notes(), o.createdByName(), o.createdAt(), o.cancelReason(), o.version(), lv,
                deliveryView(actor, o, masked), dispatch(o), masked);
    }

    private DeliveryView deliveryView(Actor actor, Row o, List<String> masked) {
        if (!o.type().equals("DELIVERY")) {
            return null;
        }
        boolean address = authz.canSeeField(actor, br.com.engenheiro.api.identityaccess.domain.FieldClass.DELIVERY_ADDRESS);
        boolean contact = authz.canSeeField(actor, br.com.engenheiro.api.identityaccess.domain.FieldClass.OPERATIONAL_CONTACT);
        if (!address) {
            masked.add("delivery.address");
            masked.add("delivery.reference");
        }
        if (!contact) {
            masked.add("delivery.contact");
        }
        return new DeliveryView(address ? o.deliveryAddress() : null, address ? o.deliveryReference() : null,
                contact ? o.deliveryContact() : null, o.windowStart(), o.windowEnd(), o.agreedPayment());
    }

    private DispatchView dispatch(Row o) {
        if (!o.type().equals("DELIVERY")) {
            return null;
        }
        return jdbc.sql("""
                        SELECT t.id AS trip_id, t.number AS trip_number, t.state AS trip_state, s.state AS stop_state,
                               u.display_name AS driver_name, v.plate
                          FROM trip_stops s JOIN trips t ON t.id = s.trip_id
                          LEFT JOIN app_users u ON u.id = t.driver_id LEFT JOIN vehicles v ON v.id = t.vehicle_id
                         WHERE s.order_id = :o AND s.state <> 'REMOVED'
                         ORDER BY s.is_open DESC, t.created_at DESC LIMIT 1
                        """).param("o", o.id()).query(DispatchView.class).optional().orElse(null);
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "order", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
