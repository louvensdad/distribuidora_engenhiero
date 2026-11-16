package br.com.engenheiro.api.inventory;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.catalogparties.products.ProductService;
import br.com.engenheiro.api.catalogparties.products.ProductService.Resolved;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.inventory.StockLedger.Balance;
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
 * Ajuste por contagem física (ADR-016/017): registra contado × físico do sistema naquele momento; a aprovação aplica
 * só a diferença (movimentos ocorridos entre a contagem e a aprovação não são apagados). Falta sai pelo custo médio;
 * sobra entra pelo custo médio, ou pelo custo informado quando o físico é zero. Capacidades de inventory.losses.
 */
@Service
public class AdjustmentService {
    static final String RESOURCE = "inventory.losses";
    private static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("x.organization_id", "x.branch_id", null,
            "x.created_by", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final ProductService products;
    private final StockLedger ledger;

    public AdjustmentService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                             ProductService products, StockLedger ledger) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.products = products;
        this.ledger = ledger;
    }

    public record CreateCommand(UUID product_id, String unit, String counted_quantity, String unit_cost, String notes) {
    }

    public record DecisionCommand(String reason) {
    }

    public record AdjustmentView(UUID id, String status, UUID product_id, String sku, String product_name,
                                 String base_unit, String counted_quantity, String system_quantity, String difference,
                                 String unit_cost, String total_cost, String notes, String branch,
                                 String created_by_name, OffsetDateTime created_at, String decided_by_name,
                                 OffsetDateTime decided_at, String decision_reason, UUID movement_id, long version,
                                 List<String> masked_fields) {
    }

    public record Page(List<AdjustmentView> items, String next_cursor) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, UUID productId, String sku,
               String productName, String baseUnit, BigDecimal countedQuantity, BigDecimal systemQuantity,
               BigDecimal difference, BigDecimal unitCost, BigDecimal totalCost, String notes, String status,
               UUID movementId, long version, UUID createdBy, String createdByName, OffsetDateTime createdAt,
               String decidedByName, OffsetDateTime decidedAt, String decisionReason) {
    }

    @Transactional
    public AdjustmentView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        return idem.execute(actor, "inventory.adjustments.create", idemKey, cmd, AdjustmentView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            Resolved p = products.resolve(actor, cmd.product_id(), cmd.unit(), "adjustment", errors);
            BigDecimal counted = Decimals.parse(cmd.counted_quantity(), Decimals.QTY_SCALE, true, "counted_quantity",
                    errors);
            String notes = Text.trim(cmd.notes());
            if (notes == null || notes.length() > 500) {
                errors.add(new FieldError("notes", "Descreva a contagem (até 500 caracteres)"));
            }
            BigDecimal unitCost = null;
            if (cmd.unit_cost() != null && !cmd.unit_cost().isBlank()) {
                if (!authz.canSeeField(actor, FieldClass.PURCHASE_COST)) {
                    errors.add(new FieldError("unit_cost", "Você não tem permissão para informar custo"));
                } else {
                    unitCost = Decimals.parseOptional(cmd.unit_cost(), Decimals.QTY_SCALE, "unit_cost", errors);
                }
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            BigDecimal countedBase = counted.multiply(p.factor());
            BigDecimal system = jdbc.sql("SELECT physical FROM inventory_balances WHERE branch_id = :b AND product_id = :p")
                    .param("b", actor.branchId()).param("p", p.productId()).query(BigDecimal.class).optional()
                    .orElse(BigDecimal.ZERO);
            BigDecimal diff = countedBase.subtract(system);
            if (diff.signum() == 0) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "A contagem confere com o sistema; nada a ajustar.",
                        List.of(new FieldError("counted_quantity", "Igual ao físico registrado")));
            }
            UUID id = Ids.newId();
            jdbc.sql("""
                            INSERT INTO inventory_adjustments (id, organization_id, branch_id, product_id, counted_quantity,
                                                               system_quantity, difference, unit_cost, notes, created_by)
                            VALUES (:id, :o, :b, :p, :c, :s, :d, :uc, :n, :by)
                            """)
                    .param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                    .param("p", p.productId()).param("c", countedBase).param("s", system).param("d", diff)
                    .param("uc", unitCost).param("n", notes).param("by", actor.userId()).update();
            audit.record(entry(actor, "inventory.adjustments.create", id, Map.of("difference", Decimals.str(diff))));
            return view(actor, find(id).orElseThrow());
        });
    }

    @Transactional
    public AdjustmentView approve(Actor actor, UUID id, String idemKey, long expectedVersion) {
        Row row = requireRow(actor, id, "approve");
        return idem.execute(actor, "inventory.adjustments.approve", idemKey,
                Map.of("id", id.toString(), "version", expectedVersion), AdjustmentView.class, () -> {
                    transition(row, expectedVersion);
                    if (row.createdBy().equals(actor.userId()) && !actor.admin()) {
                        throw new ApiException(ErrorCode.FORBIDDEN, "Quem contou não pode aprovar o próprio ajuste.");
                    }
                    Source src = new Source("inventory_adjustment", row.id(), 0);
                    Movement m;
                    if (row.difference().signum() < 0) {
                        m = ledger.issueAtAverage(actor, row.branchId(), row.productId(), row.difference().negate(),
                                "ADJUSTMENT_OUT", src, null, row.notes());
                    } else {
                        Balance b = ledger.lock(actor.organizationId(), row.branchId(), row.productId());
                        BigDecimal unit;
                        if (b.physical().signum() > 0) {
                            unit = b.value().divide(b.physical(), StockLedger.COST_SCALE, java.math.RoundingMode.HALF_UP);
                        } else if (row.unitCost() != null) {
                            unit = row.unitCost();
                        } else {
                            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                                    "Sobra com estoque zerado precisa de custo unitário informado por quem vê custo.");
                        }
                        BigDecimal total = b.physical().signum() > 0
                                ? b.value().multiply(row.difference()).divide(b.physical(), StockLedger.COST_SCALE,
                                java.math.RoundingMode.HALF_UP)
                                : row.difference().multiply(unit).setScale(StockLedger.COST_SCALE, java.math.RoundingMode.HALF_UP);
                        m = ledger.receive(actor, row.branchId(), row.productId(), row.difference(), total,
                                "ADJUSTMENT_IN", src, null, row.notes());
                    }
                    jdbc.sql("""
                                    UPDATE inventory_adjustments SET status = 'APPROVED', movement_id = :m, total_cost = :c,
                                                                     decided_by = :by, decided_at = now()
                                     WHERE id = :id
                                    """)
                            .param("m", m.id()).param("c", Decimals.money(m.totalCost())).param("by", actor.userId())
                            .param("id", row.id()).update();
                    audit.record(entry(actor, "inventory.adjustments.approve", row.id(),
                            Map.of("movement_id", m.id().toString())));
                    return view(actor, find(row.id()).orElseThrow());
                });
    }

    @Transactional
    public AdjustmentView reject(Actor actor, UUID id, String idemKey, long expectedVersion, DecisionCommand cmd) {
        Row row = requireRow(actor, id, "approve");
        String reason = cmd == null ? null : Text.trim(cmd.reason());
        if (reason == null || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório (até 500 caracteres)")));
        }
        return idem.execute(actor, "inventory.adjustments.reject", idemKey,
                Map.of("id", id.toString(), "version", expectedVersion, "reason", reason), AdjustmentView.class, () -> {
                    transition(row, expectedVersion);
                    jdbc.sql("UPDATE inventory_adjustments SET status = 'REJECTED', decided_by = :by, decided_at = now(), "
                                    + "decision_reason = :r WHERE id = :id")
                            .param("by", actor.userId()).param("r", reason).param("id", row.id()).update();
                    audit.record(entry(actor, "inventory.adjustments.reject", row.id(), Map.of("reason", reason)));
                    return view(actor, find(row.id()).orElseThrow());
                });
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String status, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        if (status != null && !List.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status inválido.");
        }
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(), COLUMNS);
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql(SELECT + " WHERE " + scope.sql()
                        + " AND (CAST(:st AS text) IS NULL OR x.status = CAST(:st AS text))"
                        + " AND (CAST(:cts AS text) IS NULL OR (x.created_at, x.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))"
                        + " ORDER BY x.created_at DESC, x.id DESC LIMIT :lim")
                .param("st", status).param("cts", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1);
        for (Map.Entry<String, Object> p : scope.params().entrySet()) {
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

    private void transition(Row row, long expectedVersion) {
        if (!row.status().equals("PENDING")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Ajuste já decidido (" + row.status() + ").");
        }
        int n = jdbc.sql("UPDATE inventory_adjustments SET version = version + 1 WHERE id = :id AND version = :v "
                        + "AND status = 'PENDING'")
                .param("id", row.id()).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O ajuste foi alterado por outra pessoa. Recarregue.");
        }
    }

    private Row requireRow(Actor actor, UUID id, String action) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, action, "inventory_adjustment", id.toString(),
                new RecordRef(row.organizationId(), row.branchId(), null, row.createdBy(), null));
        return row;
    }

    private static final String SELECT = """
            SELECT x.id, x.organization_id, x.branch_id, b.name AS branch_name, x.product_id, p.sku,
                   p.name AS product_name, p.base_unit, x.counted_quantity, x.system_quantity, x.difference,
                   x.unit_cost, x.total_cost, x.notes, x.status, x.movement_id, x.version, x.created_by,
                   cu.display_name AS created_by_name, x.created_at, du.display_name AS decided_by_name,
                   x.decided_at, x.decision_reason
              FROM inventory_adjustments x
              JOIN branches b ON b.id = x.branch_id
              JOIN products p ON p.id = x.product_id
              JOIN app_users cu ON cu.id = x.created_by
              LEFT JOIN app_users du ON du.id = x.decided_by
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE x.id = :id").param("id", id).query(Row.class).optional();
    }

    private AdjustmentView view(Actor actor, Row r) {
        boolean cost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        return new AdjustmentView(r.id(), r.status(), r.productId(), r.sku(), r.productName(), r.baseUnit(),
                Decimals.str(r.countedQuantity()), Decimals.str(r.systemQuantity()), Decimals.str(r.difference()),
                cost ? Decimals.str(r.unitCost()) : null, cost ? Decimals.moneyStr(r.totalCost()) : null, r.notes(),
                r.branchName(), r.createdByName(), r.createdAt(), r.decidedByName(), r.decidedAt(),
                r.decisionReason(), r.movementId(), r.version(), cost ? List.of() : List.of("unit_cost", "total_cost"));
    }

    private static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "inventory_adjustment", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
