package br.com.engenheiro.api.catalogparties.customers;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
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

@Service
public class CustomerService {
    static final String RESOURCE = "customers";
    private static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("c.organization_id", "c.branch_id", null,
            "c.created_by", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public CustomerService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    /** Linha interna (não é DTO público). */
    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, String name, String contact,
               String address, String personalDocument, String status, long version, OffsetDateTime createdAt,
               UUID createdBy) {
    }

    /** Visão pública com campos mascarados conforme a política de campos do ator. */
    public record CustomerView(UUID id, String name, String branch, String contact, String address,
                               String personal_document, String status, long version, OffsetDateTime created_at,
                               List<String> masked_fields) {
    }

    public record CreateCommand(String name, String contact, String address, String personalDocument) {
    }

    public record Page(List<CustomerView> items, String next_cursor) {
    }

    @Transactional
    public CustomerView create(Actor actor, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        List<FieldError> errors = new ArrayList<>();
        String name = trim(cmd.name());
        if (name == null || name.length() > 200) {
            errors.add(new FieldError("name", "Nome obrigatório (até 200 caracteres)"));
        }
        String doc = cmd.personalDocument() == null ? null : cmd.personalDocument().replaceAll("[.\\-/\\s]", "");
        if (doc != null && !doc.matches("[0-9]{11}|[0-9]{14}")) {
            errors.add(new FieldError("personal_document", "CPF (11) ou CNPJ (14) dígitos"));
        }
        // Quem não vê um campo também não o grava (evita sobrescrita às cegas).
        rejectHiddenField(actor, FieldClass.OPERATIONAL_CONTACT, cmd.contact(), "contact", errors);
        rejectHiddenField(actor, FieldClass.DELIVERY_ADDRESS, cmd.address(), "address", errors);
        rejectHiddenField(actor, FieldClass.PERSONAL_DOCUMENT, doc, "personal_document", errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO customers (id, organization_id, branch_id, name, contact, address,
                                                   personal_document, created_by)
                            VALUES (:id, :org, :branch, :name, :contact, :address, :doc, :by)
                            """)
                    .param("id", id).param("org", actor.organizationId()).param("branch", actor.branchId())
                    .param("name", name).param("contact", trim(cmd.contact())).param("address", trim(cmd.address()))
                    .param("doc", doc).param("by", actor.userId()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Documento já cadastrado.",
                    List.of(new FieldError("personal_document", "Já existe cliente com este documento")));
        }
        Map<String, Object> changes = new HashMap<>();
        changes.put("fields_set", presentFields(cmd, doc)); // só nomes de campo, nunca valores pessoais
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "customers.create", "customer", id.toString(), "SUCCESS", null, changes, "api"));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public CustomerView get(Actor actor, UUID id) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, "view"); // sem capacidade: 403 antes de qualquer 404
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, "view", "customer", id.toString(),
                new RecordRef(row.organizationId(), row.branchId(), null, row.createdBy(), null));
        return view(actor, row);
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String query, int limit, String cursor) {
        Decision d = authz.require(actor, RESOURCE, "view");
        if (limit < 1 || limit > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit deve estar entre 1 e 100.");
        }
        ScopeSql.Predicate scope = ScopeSql.of(actor, d.scopes(), COLUMNS);
        String[] c = decodeCursor(cursor);
        var spec = jdbc.sql(SELECT + " WHERE " + scope.sql()
                        + " AND c.status = 'ACTIVE'"
                        + " AND (CAST(:q AS text) IS NULL OR c.name ILIKE '%' || CAST(:q AS text) || '%')"
                        + " AND (CAST(:cname AS text) IS NULL OR (c.name, c.id) > (CAST(:cname AS text), CAST(:cid AS uuid)))"
                        + " ORDER BY c.name, c.id LIMIT :lim")
                .param("q", trim(query)).param("cname", c == null ? null : c[0]).param("cid", c == null ? null : c[1])
                .param("lim", limit + 1);
        for (Map.Entry<String, Object> p : scope.params().entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<Row> rows = spec.query(Row.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            Row last = rows.getLast();
            next = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.name() + "\u0000" + last.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new Page(rows.stream().map(r -> view(actor, r)).toList(), next);
    }

    private static final String SELECT = """
            SELECT c.id, c.organization_id, c.branch_id, b.name AS branch_name, c.name, c.contact, c.address,
                   c.personal_document, c.status, c.version, c.created_at, c.created_by
              FROM customers c JOIN branches b ON b.id = c.branch_id
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("id", id).query(Row.class).optional();
    }

    private CustomerView view(Actor actor, Row r) {
        List<String> masked = new ArrayList<>();
        String contact = visible(actor, FieldClass.OPERATIONAL_CONTACT, r.contact(), "contact", masked);
        String address = visible(actor, FieldClass.DELIVERY_ADDRESS, r.address(), "address", masked);
        String doc = visible(actor, FieldClass.PERSONAL_DOCUMENT, r.personalDocument(), "personal_document", masked);
        return new CustomerView(r.id(), r.name(), r.branchName(), contact, address, doc, r.status(), r.version(),
                r.createdAt(), masked);
    }

    private String visible(Actor actor, FieldClass f, String value, String name, List<String> masked) {
        if (authz.canSeeField(actor, f)) {
            return value;
        }
        masked.add(name);
        return null;
    }

    private void rejectHiddenField(Actor actor, FieldClass f, String value, String name, List<FieldError> errors) {
        if (value != null && !value.isBlank() && !authz.canSeeField(actor, f)) {
            errors.add(new FieldError(name, "Você não tem permissão para informar este campo"));
        }
    }

    private static List<String> presentFields(CreateCommand cmd, String doc) {
        List<String> f = new ArrayList<>(List.of("name"));
        if (trim(cmd.contact()) != null) {
            f.add("contact");
        }
        if (trim(cmd.address()) != null) {
            f.add("address");
        }
        if (doc != null) {
            f.add("personal_document");
        }
        return f;
    }

    private static String[] decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = raw.split("\u0000", 2);
            UUID.fromString(parts[1]);
            return parts;
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor inválido.");
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
