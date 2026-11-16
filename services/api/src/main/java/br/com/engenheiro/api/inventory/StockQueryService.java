package br.com.engenheiro.api.inventory;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.shared.values.Cursor;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saldos (físico, reservado, disponível) e histórico de movimentos; custo só para quem vê purchase_cost. */
@Service
public class StockQueryService {
    static final String RESOURCE = "inventory.stock";

    private final JdbcClient jdbc;
    private final AuthorizationService authz;

    public StockQueryService(JdbcClient jdbc, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    public record BalanceView(UUID product_id, String sku, String product_name, String base_unit, UUID branch_id,
                              String branch, String physical, String reserved, String available, String value,
                              String average_cost, long version, OffsetDateTime updated_at,
                              List<String> masked_fields) {
    }

    public record BalancePage(List<BalanceView> items, String next_cursor) {
    }

    public record MovementView(UUID id, String kind, UUID product_id, String sku, String product_name,
                               String base_unit, String branch, String quantity, String balance_after,
                               String unit_cost, String total_cost, String source_type, UUID source_id,
                               int source_line, UUID reversal_of, String reason, String created_by_name,
                               OffsetDateTime occurred_at, List<String> masked_fields) {
    }

    public record MovementPage(List<MovementView> items, String next_cursor) {
    }

    record BalanceRow(UUID productId, String sku, String productName, String baseUnit, UUID branchId,
                      String branchName, BigDecimal physical, BigDecimal reserved, BigDecimal value, long version,
                      OffsetDateTime updatedAt) {
    }

    record MovementRow(UUID id, String kind, UUID productId, String sku, String productName, String baseUnit,
                       String branchName, BigDecimal quantity, BigDecimal balanceAfter, BigDecimal unitCost,
                       BigDecimal totalCost, String sourceType, UUID sourceId, int sourceLine, UUID reversalOf,
                       String reason, String createdByName, OffsetDateTime occurredAt) {
    }

    @Transactional(readOnly = true)
    public BalancePage balances(Actor actor, String query, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(),
                new ScopeSql.Columns("ib.organization_id", "ib.branch_id", null, null, null));
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql("""
                        SELECT ib.product_id, p.sku, p.name AS product_name, p.base_unit, ib.branch_id,
                               b.name AS branch_name, ib.physical, ib.reserved, ib.value, ib.version, ib.updated_at
                          FROM inventory_balances ib
                          JOIN products p ON p.id = ib.product_id
                          JOIN branches b ON b.id = ib.branch_id
                         WHERE """ + scope.sql() + """
                           AND (CAST(:q AS text) IS NULL OR p.name ILIKE '%' || CAST(:q AS text) || '%'
                                OR p.sku ILIKE CAST(:q AS text) || '%')
                           AND (CAST(:cname AS text) IS NULL
                                OR (p.name, ib.product_id, ib.branch_id)
                                   > (CAST(:cname AS text), CAST(:cpid AS uuid), CAST(:cbid AS uuid)))
                         ORDER BY p.name, ib.product_id, ib.branch_id
                         LIMIT :lim
                        """)
                .param("q", Text.trim(query)).param("lim", limit + 1);
        String cname = null;
        UUID cbid = null;
        if (c != null) {
            int sep = c.sort().lastIndexOf('\u0001');
            if (sep < 0) {
                throw new br.com.engenheiro.api.shared.web.ApiException(
                        br.com.engenheiro.api.shared.web.ErrorCode.VALIDATION_ERROR, "cursor inválido.");
            }
            cname = c.sort().substring(0, sep);
            try {
                cbid = UUID.fromString(c.sort().substring(sep + 1));
            } catch (IllegalArgumentException e) {
                throw new br.com.engenheiro.api.shared.web.ApiException(
                        br.com.engenheiro.api.shared.web.ErrorCode.VALIDATION_ERROR, "cursor inválido.");
            }
        }
        spec = spec.param("cname", cname).param("cpid", c == null ? null : c.id()).param("cbid", cbid);
        for (Map.Entry<String, Object> p : scope.params().entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<BalanceRow> rows = spec.query(BalanceRow.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            BalanceRow last = rows.getLast();
            next = Cursor.encode(last.productName() + "\u0001" + last.branchId(), last.productId());
        }
        boolean cost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        return new BalancePage(rows.stream().map(r -> new BalanceView(r.productId(), r.sku(), r.productName(),
                r.baseUnit(), r.branchId(), r.branchName(), Decimals.str(r.physical()), Decimals.str(r.reserved()),
                Decimals.str(r.physical().subtract(r.reserved())), cost ? Decimals.moneyStr(r.value()) : null,
                cost && r.physical().signum() > 0
                        ? Decimals.str(r.value().divide(r.physical(), 6, RoundingMode.HALF_UP)) : null,
                r.version(), r.updatedAt(), cost ? List.of() : List.of("value", "average_cost"))).toList(), next);
    }

    @Transactional(readOnly = true)
    public MovementPage movements(Actor actor, UUID productId, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(),
                new ScopeSql.Columns("m.organization_id", "m.branch_id", null, null, null));
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql("""
                        SELECT m.id, m.kind, m.product_id, p.sku, p.name AS product_name, p.base_unit,
                               b.name AS branch_name, m.quantity, m.balance_after, m.unit_cost, m.total_cost,
                               m.source_type, m.source_id, m.source_line, m.reversal_of, m.reason,
                               u.display_name AS created_by_name, m.occurred_at
                          FROM stock_movements m
                          JOIN products p ON p.id = m.product_id
                          JOIN branches b ON b.id = m.branch_id
                          JOIN app_users u ON u.id = m.created_by
                         WHERE """ + scope.sql() + """
                           AND (CAST(:pid AS uuid) IS NULL OR m.product_id = CAST(:pid AS uuid))
                           AND (CAST(:cts AS text) IS NULL
                                OR (m.occurred_at, m.id) < (CAST(:cts AS timestamptz), CAST(:cid AS uuid)))
                         ORDER BY m.occurred_at DESC, m.id DESC LIMIT :lim
                        """)
                .param("pid", productId).param("cts", c == null ? null : c.sort())
                .param("cid", c == null ? null : c.id()).param("lim", limit + 1);
        for (Map.Entry<String, Object> p : scope.params().entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<MovementRow> rows = spec.query(MovementRow.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(jdbc.sql("SELECT CAST(occurred_at AS text) FROM stock_movements WHERE id = :id")
                    .param("id", rows.getLast().id()).query(String.class).single(), rows.getLast().id());
        }
        boolean cost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        return new MovementPage(rows.stream().map(r -> new MovementView(r.id(), r.kind(), r.productId(), r.sku(),
                r.productName(), r.baseUnit(), r.branchName(), Decimals.str(r.quantity()),
                Decimals.str(r.balanceAfter()), cost ? Decimals.str(r.unitCost()) : null,
                cost ? Decimals.moneyStr(r.totalCost()) : null, r.sourceType(), r.sourceId(), r.sourceLine(),
                r.reversalOf(), r.reason(), r.createdByName(), r.occurredAt(),
                cost ? List.of() : List.of("unit_cost", "total_cost"))).toList(), next);
    }
}
