package br.com.engenheiro.api.catalogparties.products;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Produtos e conversões de medida por produto (INV-01). Catálogo é da organização inteira. */
@Service
public class ProductService {
    static final String RESOURCE = "catalog";

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public ProductService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    record Row(UUID id, UUID organizationId, String sku, String name, String baseUnit, boolean baseUnitConfirmed,
               OffsetDateTime unitConfirmedAt, String status, long version, OffsetDateTime createdAt,
               UUID createdBy) {
    }

    record ConversionRow(String unit, BigDecimal factor, OffsetDateTime validFrom) {
    }

    public record ConversionView(String unit, String factor, OffsetDateTime valid_from) {
    }

    public record PriceView(String unit, String price, OffsetDateTime valid_from) {
    }

    public record ProductView(UUID id, String sku, String name, String base_unit, boolean base_unit_confirmed,
                              OffsetDateTime unit_confirmed_at, String status, long version,
                              List<ConversionView> conversions, List<PriceView> prices, OffsetDateTime created_at) {
    }

    public record PriceInput(String unit, String price) {
    }

    record PriceRow(String unit, BigDecimal price, OffsetDateTime validFrom) {
    }

    public record ConversionInput(String unit, String factor) {
    }

    public record CreateCommand(String sku, String name, String baseUnit, Boolean baseUnitConfirmed,
                                List<ConversionInput> conversions) {
    }

    public record Page(List<ProductView> items, String next_cursor) {
    }

    /** Produto pronto para movimentar estoque, com o fator da medida informada. */
    public record Resolved(UUID productId, String baseUnit, String unit, BigDecimal factor) {
    }

    @Transactional
    public ProductView create(Actor actor, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        List<FieldError> errors = new ArrayList<>();
        String sku = Text.trim(cmd.sku()) == null ? null : Text.trim(cmd.sku()).toUpperCase();
        if (sku == null || !sku.matches("[A-Z0-9][A-Z0-9._-]{0,39}")) {
            errors.add(new FieldError("sku", "Código obrigatório: letras, números, . _ - (até 40)"));
        }
        String name = Text.trim(cmd.name());
        if (name == null || name.length() > 200) {
            errors.add(new FieldError("name", "Nome obrigatório (até 200 caracteres)"));
        }
        if (!Units.valid(cmd.baseUnit())) {
            errors.add(new FieldError("base_unit", "Unidade-base inválida. Use: " + String.join(", ", Units.ALL)));
        }
        List<ConversionRow> conversions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ConversionInput> in = cmd.conversions() == null ? List.of() : cmd.conversions();
        for (int i = 0; i < in.size(); i++) {
            ConversionInput c = in.get(i);
            String field = "conversions[" + i + "]";
            if (!Units.valid(c.unit()) || c.unit().equals(cmd.baseUnit()) || !seen.add(c.unit())) {
                errors.add(new FieldError(field + ".unit", "Medida inválida, repetida ou igual à unidade-base"));
            }
            BigDecimal f = Decimals.parse(c.factor(), Decimals.QTY_SCALE, false, field + ".factor", errors);
            conversions.add(new ConversionRow(c.unit(), f, null));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        boolean confirmed = Boolean.TRUE.equals(cmd.baseUnitConfirmed());
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO products (id, organization_id, sku, name, base_unit, base_unit_confirmed,
                                                  unit_confirmed_by, unit_confirmed_at, created_by)
                            VALUES (:id, :org, :sku, :name, :unit, :conf, :cby, CASE WHEN :conf THEN now() END, :by)
                            """)
                    .param("id", id).param("org", actor.organizationId()).param("sku", sku).param("name", name)
                    .param("unit", cmd.baseUnit()).param("conf", confirmed)
                    .param("cby", confirmed ? actor.userId() : null).param("by", actor.userId()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Código já cadastrado.",
                    List.of(new FieldError("sku", "Já existe produto com este código")));
        }
        for (ConversionRow c : conversions) {
            insertConversion(actor, id, c.unit(), c.factor());
        }
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "catalog.products.create", "product", id.toString(), "SUCCESS", null,
                Map.of("sku", sku, "base_unit", cmd.baseUnit(), "base_unit_confirmed", confirmed,
                        "conversions", conversions.stream().map(c -> c.unit() + "=" + c.factor().toPlainString())
                                .toList()), "api"));
        return view(find(id).orElseThrow());
    }

    /** Nova vigência de conversão; a anterior fica no histórico (movimentos já gravados guardam o fator usado). */
    @Transactional
    public ProductView addConversion(Actor actor, UUID id, long expectedVersion, ConversionInput c) {
        Row row = requireRow(actor, id, "edit_draft");
        List<FieldError> errors = new ArrayList<>();
        if (!Units.valid(c.unit()) || c.unit().equals(row.baseUnit())) {
            errors.add(new FieldError("unit", "Medida inválida ou igual à unidade-base"));
        }
        BigDecimal f = Decimals.parse(c.factor(), Decimals.QTY_SCALE, false, "factor", errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        bumpVersion(actor, id, expectedVersion);
        BigDecimal previous = currentFactor(id, c.unit()).orElse(null);
        insertConversion(actor, id, c.unit(), f);
        Map<String, Object> changes = new java.util.HashMap<>();
        changes.put("unit", c.unit());
        changes.put("factor", f.toPlainString());
        changes.put("previous_factor", previous == null ? null : previous.toPlainString());
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "catalog.products.conversion", "product", id.toString(), "SUCCESS", null,
                changes, "api"));
        return view(find(id).orElseThrow());
    }

    /** Preço de venda por medida, com vigência; pedidos usam o preço vigente no momento (preço do servidor). */
    @Transactional
    public ProductView addPrice(Actor actor, UUID id, long expectedVersion, PriceInput in) {
        Row row = requireRow(actor, id, "edit_draft");
        List<FieldError> errors = new ArrayList<>();
        String unit = in.unit() == null || in.unit().isBlank() ? row.baseUnit() : in.unit();
        if (!unit.equals(row.baseUnit()) && currentFactor(id, unit).isEmpty()) {
            errors.add(new FieldError("unit", "Cadastre a conversão desta medida antes do preço"));
        }
        BigDecimal price = Decimals.parse(in.price(), Decimals.MONEY_SCALE, false, "price", errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        bumpVersion(actor, id, expectedVersion);
        BigDecimal previous = currentPrice(id, unit).orElse(null);
        jdbc.sql("INSERT INTO product_prices (id, product_id, unit, price, created_by) VALUES (:id, :p, :u, :v, :by)")
                .param("id", Ids.newId()).param("p", id).param("u", unit).param("v", price).param("by", actor.userId())
                .update();
        Map<String, Object> changes = new java.util.HashMap<>();
        changes.put("unit", unit);
        changes.put("price", price.toPlainString());
        changes.put("previous_price", previous == null ? null : previous.toPlainString());
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "catalog.products.price", "product", id.toString(), "SUCCESS", null, changes, "api"));
        return view(find(id).orElseThrow());
    }

    public Optional<BigDecimal> currentPrice(UUID productId, String unit) {
        return jdbc.sql("SELECT price FROM product_prices WHERE product_id = :p AND unit = :u "
                        + "AND valid_from <= clock_timestamp() ORDER BY valid_from DESC, id DESC LIMIT 1")
                .param("p", productId).param("u", unit).query(BigDecimal.class).optional();
    }

    /** Confirmação da unidade comercial (D01): sem ela o produto não entra/sai do estoque. */
    @Transactional
    public ProductView confirmUnit(Actor actor, UUID id, long expectedVersion) {
        Row row = requireRow(actor, id, "edit_draft");
        if (row.baseUnitConfirmed()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "A unidade deste produto já foi confirmada.");
        }
        bumpVersion(actor, id, expectedVersion);
        jdbc.sql("UPDATE products SET base_unit_confirmed = true, unit_confirmed_by = :by, unit_confirmed_at = now() "
                + "WHERE id = :id").param("by", actor.userId()).param("id", id).update();
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "catalog.products.confirm_unit", "product", id.toString(), "SUCCESS", null,
                Map.of("base_unit", row.baseUnit()), "api"));
        return view(find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public ProductView get(Actor actor, UUID id) {
        return view(requireRow(actor, id, "view"));
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String query, int limit, String cursor) {
        authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        Cursor.Key c = Cursor.decode(cursor);
        List<Row> rows = jdbc.sql(SELECT + """
                         WHERE p.organization_id = :org AND p.status = 'ACTIVE'
                           AND (CAST(:q AS text) IS NULL OR p.name ILIKE '%' || CAST(:q AS text) || '%'
                                OR p.sku ILIKE CAST(:q AS text) || '%')
                           AND (CAST(:cname AS text) IS NULL OR (p.name, p.id) > (CAST(:cname AS text), CAST(:cid AS uuid)))
                         ORDER BY p.name, p.id LIMIT :lim
                        """)
                .param("org", actor.organizationId()).param("q", Text.trim(query))
                .param("cname", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1).query(Row.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().name(), rows.getLast().id());
        }
        return new Page(rows.stream().map(this::view).toList(), next);
    }

    /**
     * Usado pelo estoque: produto ativo da organização, unidade confirmada e fator vigente da medida.
     * Erros viram erro de campo com o nome indicado.
     */
    public Resolved resolve(Actor actor, UUID productId, String unit, String field, List<FieldError> errors) {
        Row row = productId == null ? null : find(productId)
                .filter(r -> r.organizationId().equals(actor.organizationId()) && r.status().equals("ACTIVE"))
                .orElse(null);
        if (row == null) {
            errors.add(new FieldError(field + ".product_id", "Produto não encontrado"));
            return null;
        }
        if (!row.baseUnitConfirmed()) {
            errors.add(new FieldError(field + ".product_id",
                    "Unidade comercial do produto ainda não confirmada; confirme no cadastro"));
            return null;
        }
        String u = unit == null || unit.isBlank() ? row.baseUnit() : unit;
        if (u.equals(row.baseUnit())) {
            return new Resolved(row.id(), row.baseUnit(), u, BigDecimal.ONE);
        }
        Optional<BigDecimal> f = currentFactor(row.id(), u);
        if (f.isEmpty()) {
            errors.add(new FieldError(field + ".unit", "Produto sem conversão cadastrada para " + u));
            return null;
        }
        return new Resolved(row.id(), row.baseUnit(), u, f.get());
    }

    private Row requireRow(Actor actor, UUID id, String action) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, action, "product", id.toString(),
                new RecordRef(row.organizationId(), null, null, row.createdBy(), null));
        return row;
    }

    private void bumpVersion(Actor actor, UUID id, long expectedVersion) {
        int n = jdbc.sql("UPDATE products SET version = version + 1, updated_at = now(), updated_by = :by "
                        + "WHERE id = :id AND version = :v")
                .param("by", actor.userId()).param("id", id).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O produto foi alterado por outra pessoa. Recarregue.");
        }
    }

    private void insertConversion(Actor actor, UUID productId, String unit, BigDecimal factor) {
        jdbc.sql("INSERT INTO unit_conversions (id, product_id, unit, factor, created_by) "
                        + "VALUES (:id, :p, :u, :f, :by)")
                .param("id", Ids.newId()).param("p", productId).param("u", unit).param("f", factor)
                .param("by", actor.userId()).update();
    }

    private Optional<BigDecimal> currentFactor(UUID productId, String unit) {
        return jdbc.sql("SELECT factor FROM unit_conversions WHERE product_id = :p AND unit = :u "
                        + "AND valid_from <= clock_timestamp() ORDER BY valid_from DESC, id DESC LIMIT 1")
                .param("p", productId).param("u", unit).query(BigDecimal.class).optional();
    }

    private static final String SELECT = """
            SELECT p.id, p.organization_id, p.sku, p.name, p.base_unit, p.base_unit_confirmed, p.unit_confirmed_at,
                   p.status, p.version, p.created_at, p.created_by
              FROM products p
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(Row.class).optional();
    }

    private ProductView view(Row r) {
        List<ConversionView> conv = jdbc.sql("""
                        SELECT DISTINCT ON (unit) unit, factor, valid_from FROM unit_conversions
                         WHERE product_id = :p AND valid_from <= clock_timestamp()
                         ORDER BY unit, valid_from DESC, id DESC
                        """)
                .param("p", r.id()).query(ConversionRow.class).list().stream()
                .map(c -> new ConversionView(c.unit(), Decimals.str(c.factor()), c.validFrom())).toList();
        List<PriceView> prices = jdbc.sql("""
                        SELECT DISTINCT ON (unit) unit, price, valid_from FROM product_prices
                         WHERE product_id = :p AND valid_from <= clock_timestamp()
                         ORDER BY unit, valid_from DESC, id DESC
                        """)
                .param("p", r.id()).query(PriceRow.class).list().stream()
                .map(x -> new PriceView(x.unit(), Decimals.moneyStr(x.price()), x.validFrom())).toList();
        return new ProductView(r.id(), r.sku(), r.name(), r.baseUnit(), r.baseUnitConfirmed(), r.unitConfirmedAt(),
                r.status(), r.version(), conv, prices, r.createdAt());
    }
}
