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
 * Perdas por motivo (INV-05): registro sem efeito no saldo; a aprovação baixa o físico pelo custo médio, uma
 * única vez (movimento com origem única). Estorno de perda aprovada devolve a quantidade com o mesmo custo,
 * vinculado ao movimento original (INV-06). Quem registrou não aprova a própria perda, salvo administrador.
 */
@Service
public class LossService {
    static final String RESOURCE = "inventory.losses";
    static final List<String> REASONS = List.of("QUEBRA", "DETERIORACAO", "AVARIA_TRANSPORTE", "FURTO",
            "ERRO_CONTAGEM", "OUTRO");
    private static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("x.organization_id", "x.branch_id", null,
            "x.created_by", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final ProductService products;
    private final StockLedger ledger;

    public LossService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                       ProductService products, StockLedger ledger) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.products = products;
        this.ledger = ledger;
    }

    public record CreateCommand(UUID product_id, String unit, String quantity, String reason, String notes) {
    }

    public record DecisionCommand(String reason) {
    }

    public record LossView(UUID id, String status, UUID product_id, String sku, String product_name, String unit,
                           String quantity, String base_quantity, String base_unit, String reason, String notes,
                           String total_cost, String branch, String created_by_name, OffsetDateTime created_at,
                           String decided_by_name, OffsetDateTime decided_at, String decision_reason,
                           UUID movement_id, long version, List<String> masked_fields) {
    }

    public record Page(List<LossView> items, String next_cursor) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, UUID productId, String sku,
               String productName, String baseUnit, String unit, BigDecimal quantity, BigDecimal baseQuantity,
               String reason, String notes, String status, UUID movementId, BigDecimal totalCost, long version,
               UUID createdBy, String createdByName, OffsetDateTime createdAt, String decidedByName,
               OffsetDateTime decidedAt, String decisionReason) {
    }

    @Transactional
    public LossView create(Actor actor, String idemKey, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        return idem.execute(actor, "inventory.losses.create", idemKey, cmd, LossView.class, () -> {
            List<FieldError> errors = new ArrayList<>();
            Resolved p = products.resolve(actor, cmd.product_id(), cmd.unit(), "loss", errors);
            BigDecimal qty = Decimals.parse(cmd.quantity(), Decimals.QTY_SCALE, false, "quantity", errors);
            if (cmd.reason() == null || !REASONS.contains(cmd.reason())) {
                errors.add(new FieldError("reason", "Motivo inválido. Use: " + String.join(", ", REASONS)));
            }
            String notes = Text.trim(cmd.notes());
            if (notes != null && notes.length() > 500) {
                errors.add(new FieldError("notes", "Até 500 caracteres"));
            }
            if ("OUTRO".equals(cmd.reason()) && notes == null) {
                errors.add(new FieldError("notes", "Descreva a perda quando o motivo for OUTRO"));
            }
            if (!errors.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
            }
            UUID id = Ids.newId();
            jdbc.sql("""
                            INSERT INTO inventory_losses (id, organization_id, branch_id, product_id, unit, factor,
                                                          quantity, base_quantity, reason, notes, created_by)
                            VALUES (:id, :o, :b, :p, :u, :f, :q, :bq, :r, :n, :by)
                            """)
                    .param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                    .param("p", p.productId()).param("u", p.unit()).param("f", p.factor()).param("q", qty)
                    .param("bq", qty.multiply(p.factor())).param("r", cmd.reason()).param("n", notes)
                    .param("by", actor.userId()).update();
            audit.record(entry(actor, "inventory.losses.create", id, Map.of("reason", cmd.reason(),
                    "base_quantity", Decimals.str(qty.multiply(p.factor())))));
            return view(actor, find(id).orElseThrow());
        });
    }

    @Transactional
    public LossView approve(Actor actor, UUID id, String idemKey, long expectedVersion) {
        Row row = requireRow(actor, id, "approve");
        return idem.execute(actor, "inventory.losses.approve", idemKey, Map.of("id", id.toString(),
                "version", expectedVersion), LossView.class, () -> {
            transition(actor, row, expectedVersion, "PENDING");
            if (row.createdBy().equals(actor.userId()) && !actor.admin()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Quem registrou a perda não pode aprová-la.");
            }
            Movement m = ledger.issueAtAverage(actor, row.branchId(), row.productId(), row.baseQuantity(), "LOSS",
                    new Source("inventory_loss", row.id(), 0), null, row.reason());
            jdbc.sql("""
                            UPDATE inventory_losses SET status = 'APPROVED', movement_id = :m, total_cost = :c,
                                                        decided_by = :by, decided_at = now()
                             WHERE id = :id
                            """)
                    .param("m", m.id()).param("c", Decimals.money(m.totalCost().negate())).param("by", actor.userId())
                    .param("id", row.id()).update();
            audit.record(entry(actor, "inventory.losses.approve", row.id(), Map.of("movement_id", m.id().toString())));
            return view(actor, find(row.id()).orElseThrow());
        });
    }

    @Transactional
    public LossView reject(Actor actor, UUID id, String idemKey, long expectedVersion, DecisionCommand cmd) {
        Row row = requireRow(actor, id, "approve");
        String reason = requireReason(cmd);
        return idem.execute(actor, "inventory.losses.reject", idemKey, Map.of("id", id.toString(),
                "version", expectedVersion, "reason", reason), LossView.class, () -> {
            transition(actor, row, expectedVersion, "PENDING");
            jdbc.sql("UPDATE inventory_losses SET status = 'REJECTED', decided_by = :by, decided_at = now(), "
                            + "decision_reason = :r WHERE id = :id")
                    .param("by", actor.userId()).param("r", reason).param("id", row.id()).update();
            audit.record(entry(actor, "inventory.losses.reject", row.id(), Map.of("reason", reason)));
            return view(actor, find(row.id()).orElseThrow());
        });
    }

    /** Estorno de perda aprovada por engano: volta a quantidade com o mesmo custo do movimento original. */
    @Transactional
    public LossView reverse(Actor actor, UUID id, String idemKey, long expectedVersion, DecisionCommand cmd) {
        Row row = requireRow(actor, id, "approve");
        String reason = requireReason(cmd);
        return idem.execute(actor, "inventory.losses.reverse", idemKey, Map.of("id", id.toString(),
                "version", expectedVersion, "reason", reason), LossView.class, () -> {
            transition(actor, row, expectedVersion, "APPROVED");
            BigDecimal originalCost = jdbc.sql("SELECT total_cost FROM stock_movements WHERE id = :m")
                    .param("m", row.movementId()).query(BigDecimal.class).single().negate();
            Movement m = ledger.receive(actor, row.branchId(), row.productId(), row.baseQuantity(), originalCost,
                    "LOSS_REVERSAL", new Source("inventory_loss", row.id(), 0), row.movementId(), reason);
            jdbc.sql("UPDATE inventory_losses SET status = 'REVERSED', decision_reason = :r WHERE id = :id")
                    .param("r", reason).param("id", row.id()).update();
            audit.record(entry(actor, "inventory.losses.reverse", row.id(), Map.of("reason", reason,
                    "movement_id", m.id().toString(), "reversal_of", row.movementId().toString())));
            return view(actor, find(row.id()).orElseThrow());
        });
    }

    @Transactional(readOnly = true)
    public LossView get(Actor actor, UUID id) {
        return view(actor, requireRow(actor, id, "view"));
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String status, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        if (status != null && !List.of("PENDING", "APPROVED", "REJECTED", "REVERSED").contains(status)) {
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
            next = Cursor.encode(jdbc.sql("SELECT CAST(created_at AS text) FROM inventory_losses WHERE id = :id")
                    .param("id", rows.getLast().id()).query(String.class).single(), rows.getLast().id());
        }
        return new Page(rows.stream().map(r -> view(actor, r)).toList(), next);
    }

    private void transition(Actor actor, Row row, long expectedVersion, String from) {
        if (!row.status().equals(from)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Perda está em " + row.status() + ".");
        }
        int n = jdbc.sql("UPDATE inventory_losses SET version = version + 1 WHERE id = :id AND version = :v "
                        + "AND status = :from")
                .param("id", row.id()).param("v", expectedVersion).param("from", from).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A perda foi alterada por outra pessoa. Recarregue.");
        }
    }

    private static String requireReason(DecisionCommand cmd) {
        String r = cmd == null ? null : Text.trim(cmd.reason());
        if (r == null || r.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório (até 500 caracteres)")));
        }
        return r;
    }

    private Row requireRow(Actor actor, UUID id, String action) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, action, "inventory_loss", id.toString(),
                new RecordRef(row.organizationId(), row.branchId(), null, row.createdBy(), null));
        return row;
    }

    private static final String SELECT = """
            SELECT x.id, x.organization_id, x.branch_id, b.name AS branch_name, x.product_id, p.sku,
                   p.name AS product_name, p.base_unit, x.unit, x.quantity, x.base_quantity, x.reason, x.notes,
                   x.status, x.movement_id, x.total_cost, x.version, x.created_by, cu.display_name AS created_by_name,
                   x.created_at, du.display_name AS decided_by_name, x.decided_at, x.decision_reason
              FROM inventory_losses x
              JOIN branches b ON b.id = x.branch_id
              JOIN products p ON p.id = x.product_id
              JOIN app_users cu ON cu.id = x.created_by
              LEFT JOIN app_users du ON du.id = x.decided_by
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE x.id = :id").param("id", id).query(Row.class).optional();
    }

    private LossView view(Actor actor, Row r) {
        boolean cost = authz.canSeeField(actor, FieldClass.PURCHASE_COST);
        return new LossView(r.id(), r.status(), r.productId(), r.sku(), r.productName(), r.unit(),
                Decimals.str(r.quantity()), Decimals.str(r.baseQuantity()), r.baseUnit(), r.reason(), r.notes(),
                cost ? Decimals.moneyStr(r.totalCost()) : null, r.branchName(), r.createdByName(), r.createdAt(),
                r.decidedByName(), r.decidedAt(), r.decisionReason(), r.movementId(), r.version(),
                cost ? List.of() : List.of("total_cost"));
    }

    private static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                action, "inventory_loss", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
