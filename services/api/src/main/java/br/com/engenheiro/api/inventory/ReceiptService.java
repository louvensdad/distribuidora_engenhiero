package br.com.engenheiro.api.inventory;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.catalogparties.products.ProductService;
import br.com.engenheiro.api.catalogparties.products.ProductService.Resolved;
import br.com.engenheiro.api.catalogparties.suppliers.SupplierService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
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
 * Recebimento de fornecedor (INV-02): quem recebe registra quantidades (sem ver custo, se não tiver acesso a
 * purchase_cost); quem confere confirma com custo e frete, e só então a mercadoria entra no estoque.
 * Frete de compra é rateado nas linhas pelo valor da mercadoria e incorporado ao custo (não vira despesa).
 */
@Service
public class ReceiptService {
    static final String RESOURCE = "inventory.receipts";
    private static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("r.organization_id", "r.branch_id", null,
            "r.created_by", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final ProductService products;
    private final SupplierService suppliers;
    private final StockLedger ledger;

    public ReceiptService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                          ProductService products, SupplierService suppliers, StockLedger ledger) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.products = products;
        this.suppliers = suppliers;
        this.ledger = ledger;
    }

    // ---- contrato ----
    public record LineInput(UUID product_id, String unit, String quantity_expected, String quantity_received,
                            String lot, String unit_cost) {
    }

    public record CreateCommand(UUID supplier_id, String document_number, String notes, String freight_amount,
                                List<LineInput> lines) {
    }

    public record LineCost(int line_no, String unit_cost) {
    }

    public record ConfirmCommand(String freight_amount, List<LineCost> lines) {
    }

    public record ReturnCommand(int line_no, String quantity, String reason) {
    }

    public record LineView(int line_no, UUID product_id, String sku, String product_name, String unit,
                           String factor, String quantity_expected, String quantity_received, String base_quantity,
                           String base_unit, String difference, String lot, String unit_cost, String goods_cost,
                           String freight_share, String returned_base_quantity) {
    }

    public record ReceiptView(UUID id, String status, UUID supplier_id, String supplier_name, String branch,
                              String document_number, String notes, String freight_amount, String total_cost,
                              OffsetDateTime received_at, String created_by_name, OffsetDateTime confirmed_at,
                              String confirmed_by_name, long version, List<LineView> lines,
                              List<String> masked_fields) {
    }

    public record ReturnResult(UUID receipt_id, int line_no, UUID movement_id, String base_quantity,
                               String total_cost, List<String> masked_fields) {
    }

    public record Page(List<ReceiptView> items, String next_cursor) {
    }

    // ---- linhas internas ----
    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, UUID supplierId, String supplierName,
               String status, String documentNumber, String notes, BigDecimal freightAmount, BigDecimal totalCost,
               OffsetDateTime receivedAt, UUID createdBy, String createdByName, OffsetDateTime confirmedAt,
               String confirmedByName, long version) {
    }

    record LineRow(UUID id, int lineNo, UUID productId, String sku, String productName, String baseUnit, String unit,
                   BigDecimal factor, BigDecimal quantityExpected, BigDecimal quantityReceived,
                   BigDecimal baseQuantity, String lot, BigDecimal unitCost, BigDecimal goodsCost,
                   BigDecimal freightShare, BigDecimal returnedBaseQty) {
    }

    @Transactional
    public ReceiptView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        return idem.execute(actor, "inventory.receipts.create", idemKey, cmd, ReceiptView.class, () -> doCreate(actor, cmd));
    }

    private ReceiptView doCreate(Actor actor, CreateCommand cmd) {
        boolean seesCost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        List<FieldError> errors = new ArrayList<>();
        if (!suppliers.existsActive(actor, cmd.supplier_id())) {
            errors.add(new FieldError("supplier_id", "Fornecedor não encontrado"));
        }
        String doc = Text.trim(cmd.document_number());
        if (doc != null && doc.length() > 60) {
            errors.add(new FieldError("document_number", "Até 60 caracteres"));
        }
        String notes = Text.trim(cmd.notes());
        if (notes != null && notes.length() > 500) {
            errors.add(new FieldError("notes", "Até 500 caracteres"));
        }
        BigDecimal freight = null;
        if (cmd.freight_amount() != null && !cmd.freight_amount().isBlank()) {
            if (!seesCost) {
                errors.add(new FieldError("freight_amount", "Você não tem permissão para informar custo"));
            } else {
                freight = Decimals.parseOptional(cmd.freight_amount(), Decimals.MONEY_SCALE, "freight_amount", errors);
            }
        }
        List<LineInput> lines = cmd.lines() == null ? List.of() : cmd.lines();
        if (lines.isEmpty() || lines.size() > 100) {
            errors.add(new FieldError("lines", "Informe de 1 a 100 linhas"));
        }
        record Parsed(Resolved product, BigDecimal expected, BigDecimal received, String lot, BigDecimal unitCost) {
        }
        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LineInput l = lines.get(i);
            String f = "lines[" + i + "]";
            Resolved p = products.resolve(actor, l.product_id(), l.unit(), f, errors);
            BigDecimal exp = Decimals.parseOptional(l.quantity_expected(), Decimals.QTY_SCALE, f + ".quantity_expected",
                    errors);
            BigDecimal rec = Decimals.parse(l.quantity_received(), Decimals.QTY_SCALE, true, f + ".quantity_received",
                    errors);
            String lot = Text.trim(l.lot());
            if (lot != null && lot.length() > 60) {
                errors.add(new FieldError(f + ".lot", "Até 60 caracteres"));
            }
            BigDecimal cost = null;
            if (l.unit_cost() != null && !l.unit_cost().isBlank()) {
                if (!seesCost) {
                    errors.add(new FieldError(f + ".unit_cost", "Você não tem permissão para informar custo"));
                } else {
                    cost = Decimals.parseOptional(l.unit_cost(), Decimals.QTY_SCALE, f + ".unit_cost", errors);
                }
            }
            parsed.add(new Parsed(p, exp, rec, lot, cost));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO inventory_receipts (id, organization_id, branch_id, supplier_id, document_number,
                                                        notes, freight_amount, created_by)
                        VALUES (:id, :o, :b, :s, :doc, :notes, :fr, :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                .param("s", cmd.supplier_id()).param("doc", doc).param("notes", notes).param("fr", freight)
                .param("by", actor.userId()).update();
        for (int i = 0; i < parsed.size(); i++) {
            Parsed p = parsed.get(i);
            jdbc.sql("""
                            INSERT INTO inventory_receipt_lines (id, receipt_id, line_no, product_id, unit, factor,
                                                                 quantity_expected, quantity_received, base_quantity,
                                                                 lot, unit_cost)
                            VALUES (:id, :r, :n, :p, :u, :f, :e, :q, :bq, :lot, :c)
                            """)
                    .param("id", Ids.newId()).param("r", id).param("n", i + 1).param("p", p.product().productId())
                    .param("u", p.product().unit()).param("f", p.product().factor()).param("e", p.expected())
                    .param("q", p.received()).param("bq", p.received().multiply(p.product().factor()))
                    .param("lot", p.lot()).param("c", p.unitCost()).update();
        }
        audit.record(entry(actor, "inventory.receipts.create", id, Map.of("lines", parsed.size(),
                "cost_informed", parsed.stream().anyMatch(p -> p.unitCost() != null))));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional
    public ReceiptView confirm(Actor actor, UUID id, String idemKey, long expectedVersion, ConfirmCommand cmd) {
        Row row = requireRow(actor, id, "confirm");
        ConfirmCommand body = cmd == null ? new ConfirmCommand(null, List.of()) : cmd;
        Map<String, Object> request = new HashMap<>();
        request.put("id", id.toString());
        request.put("version", expectedVersion);
        request.put("body", body);
        return idem.execute(actor, "inventory.receipts.confirm", idemKey, request, ReceiptView.class,
                () -> doConfirm(actor, row, expectedVersion, body));
    }

    private ReceiptView doConfirm(Actor actor, Row row, long expectedVersion, ConfirmCommand cmd) {
        if (!row.status().equals("DRAFT")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Recebimento já confirmado.");
        }
        int n = jdbc.sql("UPDATE inventory_receipts SET version = version + 1 WHERE id = :id AND version = :v "
                        + "AND status = 'DRAFT'")
                .param("id", row.id()).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O recebimento foi alterado. Recarregue.");
        }
        boolean seesCost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        List<FieldError> errors = new ArrayList<>();
        BigDecimal freight = row.freightAmount();
        boolean costInput = (cmd.freight_amount() != null && !cmd.freight_amount().isBlank())
                || (cmd.lines() != null && !cmd.lines().isEmpty());
        if (costInput && !seesCost) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Você não tem permissão para informar custo.",
                    List.of(new FieldError("lines", "Custo exige acesso a custo de compra")));
        }
        if (cmd.freight_amount() != null && !cmd.freight_amount().isBlank()) {
            freight = Decimals.parseOptional(cmd.freight_amount(), Decimals.MONEY_SCALE, "freight_amount", errors);
        }
        List<LineRow> lines = lines(row.id());
        Map<Integer, BigDecimal> costs = new HashMap<>();
        for (LineRow l : lines) {
            costs.put(l.lineNo(), l.unitCost());
        }
        Set<Integer> seen = new HashSet<>();
        List<LineCost> inputs = cmd.lines() == null ? List.of() : cmd.lines();
        for (int i = 0; i < inputs.size(); i++) {
            LineCost c = inputs.get(i);
            if (!costs.containsKey(c.line_no()) || !seen.add(c.line_no())) {
                errors.add(new FieldError("lines[" + i + "].line_no", "Linha inexistente ou repetida"));
                continue;
            }
            costs.put(c.line_no(), Decimals.parse(c.unit_cost(), Decimals.QTY_SCALE, true,
                    "lines[" + i + "].unit_cost", errors));
        }
        for (LineRow l : lines) {
            if (l.quantityReceived().signum() > 0 && costs.get(l.lineNo()) == null && errors.isEmpty()) {
                errors.add(new FieldError("lines", "Custo unitário pendente na linha " + l.lineNo()
                        + "; quem tem acesso a custo precisa conferir"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        BigDecimal freightTotal = freight == null ? BigDecimal.ZERO : freight;

        // Custo da mercadoria por linha (centavos HALF_UP, sem arredondar o unitário antes).
        List<LineRow> active = lines.stream().filter(l -> l.quantityReceived().signum() > 0).toList();
        Map<Integer, BigDecimal> goods = new HashMap<>();
        BigDecimal goodsTotal = BigDecimal.ZERO;
        BigDecimal qtyTotal = BigDecimal.ZERO;
        for (LineRow l : active) {
            BigDecimal g = Decimals.money(l.quantityReceived().multiply(costs.get(l.lineNo())));
            goods.put(l.lineNo(), g);
            goodsTotal = goodsTotal.add(g);
            qtyTotal = qtyTotal.add(l.baseQuantity());
        }
        // Rateio do frete pelo valor da mercadoria (ou pela quantidade, se a mercadoria não tiver valor);
        // a diferença de centavos vai para a última linha, mantendo a soma igual ao frete.
        Map<Integer, BigDecimal> share = new HashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < active.size(); i++) {
            LineRow l = active.get(i);
            BigDecimal s;
            if (i == active.size() - 1) {
                s = freightTotal.subtract(allocated);
            } else if (goodsTotal.signum() > 0) {
                s = freightTotal.multiply(goods.get(l.lineNo())).divide(goodsTotal, 2, RoundingMode.HALF_UP);
            } else {
                s = freightTotal.multiply(l.baseQuantity()).divide(qtyTotal, 2, RoundingMode.HALF_UP);
            }
            share.put(l.lineNo(), s);
            allocated = allocated.add(s);
        }
        if (active.isEmpty() && freightTotal.signum() > 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Frete sem mercadoria recebida.",
                    List.of(new FieldError("freight_amount", "Nenhuma linha com quantidade recebida")));
        }
        // Bloqueio dos saldos em ordem fixa de produto para evitar impasse entre recebimentos simultâneos.
        List<LineRow> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparing(LineRow::productId).thenComparing(LineRow::lineNo));
        for (LineRow l : ordered) {
            BigDecimal g = goods.getOrDefault(l.lineNo(), BigDecimal.ZERO.setScale(2));
            BigDecimal s = share.getOrDefault(l.lineNo(), BigDecimal.ZERO.setScale(2));
            jdbc.sql("UPDATE inventory_receipt_lines SET unit_cost = :c, goods_cost = :g, freight_share = :s "
                            + "WHERE id = :id")
                    .param("c", costs.get(l.lineNo()) == null ? BigDecimal.ZERO : costs.get(l.lineNo()))
                    .param("g", g).param("s", s).param("id", l.id()).update();
            if (l.quantityReceived().signum() > 0) {
                ledger.receive(actor, row.branchId(), l.productId(), l.baseQuantity(), g.add(s), "RECEIPT",
                        new Source("inventory_receipt", row.id(), l.lineNo()), null, null);
            }
        }
        jdbc.sql("""
                        UPDATE inventory_receipts SET status = 'CONFIRMED', freight_amount = :fr, total_cost = :tc,
                                                      confirmed_by = :by, confirmed_at = now()
                         WHERE id = :id
                        """)
                .param("fr", freightTotal).param("tc", goodsTotal.add(freightTotal)).param("by", actor.userId())
                .param("id", row.id()).update();
        audit.record(entry(actor, "inventory.receipts.confirm", row.id(), Map.of("lines", lines.size(),
                "freight_informed", freightTotal.signum() > 0))); // valores de custo ficam fora da trilha
        return view(actor, find(row.id()).orElseThrow());
    }

    /** Devolução ao fornecedor (INV-06): vinculada à linha de origem, pelo custo histórico dela. */
    @Transactional
    public ReturnResult returnToSupplier(Actor actor, UUID id, String idemKey, ReturnCommand cmd) {
        Row row = requireRow(actor, id, "reverse");
        Map<String, Object> request = new HashMap<>();
        request.put("id", id.toString());
        request.put("body", cmd);
        return idem.execute(actor, "inventory.receipts.return", idemKey, request, ReturnResult.class,
                () -> doReturn(actor, row, cmd));
    }

    private ReturnResult doReturn(Actor actor, Row row, ReturnCommand cmd) {
        if (!row.status().equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só recebimento confirmado pode ser devolvido.");
        }
        List<FieldError> errors = new ArrayList<>();
        String reason = Text.trim(cmd.reason());
        if (reason == null || reason.length() > 500) {
            errors.add(new FieldError("reason", "Motivo obrigatório (até 500 caracteres)"));
        }
        BigDecimal qty = Decimals.parse(cmd.quantity(), Decimals.QTY_SCALE, false, "quantity", errors);
        LineRow line = jdbc.sql(LINES + " WHERE l.receipt_id = :r AND l.line_no = :n FOR UPDATE OF l")
                .param("r", row.id()).param("n", cmd.line_no()).query(LineRow.class).optional().orElse(null);
        if (line == null) {
            errors.add(new FieldError("line_no", "Linha inexistente"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        BigDecimal baseQty = qty.multiply(line.factor()); // quantidade na medida da linha de origem
        if (baseQty.compareTo(line.baseQuantity().subtract(line.returnedBaseQty())) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Quantidade maior que o saldo recebido da linha.",
                    List.of(new FieldError("quantity", "Máximo " + Decimals.str(
                            line.baseQuantity().subtract(line.returnedBaseQty()).divide(line.factor(), 6,
                                    RoundingMode.DOWN)) + " " + line.unit())));
        }
        UUID origin = jdbc.sql("SELECT id FROM stock_movements WHERE source_type = 'inventory_receipt' "
                        + "AND source_id = :r AND source_line = :n AND kind = 'RECEIPT'")
                .param("r", row.id()).param("n", line.lineNo()).query(UUID.class).single();
        BigDecimal histUnit = line.goodsCost().add(line.freightShare())
                .divide(line.baseQuantity(), StockLedger.COST_SCALE, RoundingMode.HALF_UP);
        UUID returnId = Ids.newId();
        Movement m = ledger.issueAtCost(actor, row.branchId(), line.productId(), baseQty, histUnit,
                "SUPPLIER_RETURN", new Source("supplier_return", returnId, line.lineNo()), origin, reason);
        jdbc.sql("UPDATE inventory_receipt_lines SET returned_base_qty = returned_base_qty + :q WHERE id = :id")
                .param("q", baseQty).param("id", line.id()).update();
        audit.record(entry(actor, "inventory.receipts.return", row.id(), Map.of("line_no", line.lineNo(),
                "base_quantity", Decimals.str(baseQty), "movement_id", m.id().toString(), "reason", reason)));
        boolean seesCost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        return new ReturnResult(row.id(), line.lineNo(), m.id(), Decimals.str(baseQty),
                seesCost ? Decimals.moneyStr(m.totalCost().negate()) : null,
                seesCost ? List.of() : List.of("total_cost"));
    }

    @Transactional(readOnly = true)
    public ReceiptView get(Actor actor, UUID id) {
        return view(actor, requireRow(actor, id, "view"));
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String status, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        if (status != null && !status.equals("DRAFT") && !status.equals("CONFIRMED")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status deve ser DRAFT ou CONFIRMED.");
        }
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(), COLUMNS);
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql(SELECT + " WHERE " + scope.sql()
                        + " AND (CAST(:st AS text) IS NULL OR r.status = CAST(:st AS text))"
                        + " AND (CAST(:cts AS text) IS NULL OR (r.created_at, r.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))"
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :lim")
                .param("st", status).param("cts", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1);
        for (Map.Entry<String, Object> p : scope.params().entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<Row> rows = spec.query(Row.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(jdbc.sql("SELECT CAST(created_at AS text) FROM inventory_receipts WHERE id = :id")
                    .param("id", rows.getLast().id()).query(String.class).single(), rows.getLast().id());
        }
        return new Page(rows.stream().map(r -> view(actor, r)).toList(), next);
    }

    private Row requireRow(Actor actor, UUID id, String action) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, action, "inventory_receipt", id.toString(),
                new RecordRef(row.organizationId(), row.branchId(), null, row.createdBy(), null));
        return row;
    }

    private static final String SELECT = """
            SELECT r.id, r.organization_id, r.branch_id, b.name AS branch_name, r.supplier_id, s.name AS supplier_name,
                   r.status, r.document_number, r.notes, r.freight_amount, r.total_cost, r.received_at, r.created_by,
                   cu.display_name AS created_by_name, r.confirmed_at, ku.display_name AS confirmed_by_name, r.version
              FROM inventory_receipts r
              JOIN branches b ON b.id = r.branch_id
              JOIN suppliers s ON s.id = r.supplier_id
              JOIN app_users cu ON cu.id = r.created_by
              LEFT JOIN app_users ku ON ku.id = r.confirmed_by
            """;

    private static final String LINES = """
            SELECT l.id, l.line_no, l.product_id, p.sku, p.name AS product_name, p.base_unit, l.unit, l.factor,
                   l.quantity_expected, l.quantity_received, l.base_quantity, l.lot, l.unit_cost, l.goods_cost,
                   l.freight_share, l.returned_base_qty
              FROM inventory_receipt_lines l JOIN products p ON p.id = l.product_id
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(Row.class).optional();
    }

    private List<LineRow> lines(UUID receiptId) {
        return jdbc.sql(LINES + " WHERE l.receipt_id = :r ORDER BY l.line_no").param("r", receiptId)
                .query(LineRow.class).list();
    }

    private ReceiptView view(Actor actor, Row r) {
        boolean cost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        List<LineView> lv = lines(r.id()).stream().map(l -> new LineView(l.lineNo(), l.productId(), l.sku(),
                l.productName(), l.unit(), Decimals.str(l.factor()), Decimals.str(l.quantityExpected()),
                Decimals.str(l.quantityReceived()), Decimals.str(l.baseQuantity()), l.baseUnit(),
                l.quantityExpected() == null ? null : Decimals.str(l.quantityReceived().subtract(l.quantityExpected())),
                l.lot(), cost ? Decimals.str(l.unitCost()) : null, cost ? Decimals.moneyStr(l.goodsCost()) : null,
                cost ? Decimals.moneyStr(l.freightShare()) : null, Decimals.str(l.returnedBaseQty()))).toList();
        List<String> masked = cost ? List.of()
                : List.of("freight_amount", "total_cost", "lines.unit_cost", "lines.goods_cost", "lines.freight_share");
        return new ReceiptView(r.id(), r.status(), r.supplierId(), r.supplierName(), r.branchName(),
                r.documentNumber(), r.notes(), cost ? Decimals.moneyStr(r.freightAmount()) : null,
                cost ? Decimals.moneyStr(r.totalCost()) : null, r.receivedAt(), r.createdByName(), r.confirmedAt(),
                r.confirmedByName(), r.version(), lv, masked);
    }

    private static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "inventory_receipt", id.toString(), "SUCCESS", null, changes, "api");
    }
}
