package br.com.engenheiro.api.catalogparties.suppliers;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Cursor;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fornecedor interno (INV-02). Cadastro da organização; não cria usuário externo. */
@Service
public class SupplierService {
    static final String RESOURCE = "suppliers";

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public SupplierService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    record Row(UUID id, UUID organizationId, String name, String contact, String personalDocument, String status,
               long version, OffsetDateTime createdAt, UUID createdBy) {
    }

    public record SupplierView(UUID id, String name, String contact, String personal_document, String status,
                               long version, OffsetDateTime created_at, List<String> masked_fields) {
    }

    public record Page(List<SupplierView> items, String next_cursor) {
    }

    public record CreateCommand(String name, String contact, String personalDocument) {
    }

    public record UpdateCommand(String name, String contact, String personalDocument, String status) {
    }

    /** Edição com versão; campo oculto não é enviado e fica como está; ARCHIVED some das listas e de novos recebimentos. */
    @Transactional
    public SupplierView update(Actor actor, UUID id, long expectedVersion, UpdateCommand cmd) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, "edit_draft");
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, "edit_draft", "supplier", id.toString(),
                new RecordRef(row.organizationId(), null, null, row.createdBy(), null));
        List<FieldError> errors = new ArrayList<>();
        String name = Text.trim(cmd.name());
        if (name == null || name.length() > 200) {
            errors.add(new FieldError("name", "Nome obrigatório (até 200 caracteres)"));
        }
        String status = cmd.status() == null ? row.status() : cmd.status();
        if (!List.of("ACTIVE", "ARCHIVED").contains(status)) {
            errors.add(new FieldError("status", "ACTIVE ou ARCHIVED"));
        }
        String doc = Text.document(cmd.personalDocument());
        if (doc != null && !doc.matches("[0-9]{11}|[0-9]{14}")) {
            errors.add(new FieldError("personal_document", "CPF (11) ou CNPJ (14) dígitos"));
        }
        rejectHidden(actor, FieldClass.OPERATIONAL_CONTACT, Text.trim(cmd.contact()), "contact", errors);
        rejectHidden(actor, FieldClass.PERSONAL_DOCUMENT, doc, "personal_document", errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        String contact = authz.canSeeField(actor, FieldClass.OPERATIONAL_CONTACT) ? Text.trim(cmd.contact()) : row.contact();
        String document = authz.canSeeField(actor, FieldClass.PERSONAL_DOCUMENT) ? doc : row.personalDocument();
        List<String> changed = new ArrayList<>();
        if (!name.equals(row.name())) {
            changed.add("name");
        }
        if (!java.util.Objects.equals(contact, row.contact())) {
            changed.add("contact");
        }
        if (!java.util.Objects.equals(document, row.personalDocument())) {
            changed.add("personal_document");
        }
        if (!status.equals(row.status())) {
            changed.add("status");
        }
        int n;
        try {
            n = jdbc.sql("""
                            UPDATE suppliers SET name = :name, contact = :contact, personal_document = :doc, status = :st,
                                   version = version + 1, updated_at = now(), updated_by = :by
                             WHERE id = :id AND version = :v
                            """)
                    .param("name", name).param("contact", contact).param("doc", document).param("st", status)
                    .param("by", actor.userId()).param("id", id).param("v", expectedVersion).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Documento já cadastrado.",
                    List.of(new FieldError("personal_document", "Já existe fornecedor com este documento")));
        }
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O fornecedor foi alterado por outra pessoa. Recarregue.");
        }
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), status.equals("ARCHIVED") && changed.contains("status") ? "suppliers.archive"
                        : "suppliers.update", "supplier", id.toString(), "SUCCESS", null,
                Map.of("fields_changed", changed), "api"));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional
    public SupplierView create(Actor actor, CreateCommand cmd) {
        authz.require(actor, RESOURCE, "create");
        List<FieldError> errors = new ArrayList<>();
        String name = Text.trim(cmd.name());
        if (name == null || name.length() > 200) {
            errors.add(new FieldError("name", "Nome obrigatório (até 200 caracteres)"));
        }
        String doc = Text.document(cmd.personalDocument());
        if (doc != null && !doc.matches("[0-9]{11}|[0-9]{14}")) {
            errors.add(new FieldError("personal_document", "CPF (11) ou CNPJ (14) dígitos"));
        }
        rejectHidden(actor, FieldClass.OPERATIONAL_CONTACT, Text.trim(cmd.contact()), "contact", errors);
        rejectHidden(actor, FieldClass.PERSONAL_DOCUMENT, doc, "personal_document", errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("INSERT INTO suppliers (id, organization_id, name, contact, personal_document, created_by) "
                            + "VALUES (:id, :org, :name, :contact, :doc, :by)")
                    .param("id", id).param("org", actor.organizationId()).param("name", name)
                    .param("contact", Text.trim(cmd.contact())).param("doc", doc).param("by", actor.userId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Documento já cadastrado.",
                    List.of(new FieldError("personal_document", "Já existe fornecedor com este documento")));
        }
        List<String> fields = new ArrayList<>(List.of("name"));
        if (Text.trim(cmd.contact()) != null) {
            fields.add("contact");
        }
        if (doc != null) {
            fields.add("personal_document");
        }
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "suppliers.create", "supplier", id.toString(), "SUCCESS", null,
                Map.of("fields_set", fields), "api"));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public SupplierView get(Actor actor, UUID id) {
        Row row = find(id).orElse(null);
        if (row == null) {
            authz.require(actor, RESOURCE, "view");
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, RESOURCE, "view", "supplier", id.toString(),
                new RecordRef(row.organizationId(), null, null, row.createdBy(), null));
        return view(actor, row);
    }

    @Transactional(readOnly = true)
    public Page list(Actor actor, String query, int limit, String cursor) {
        authz.require(actor, RESOURCE, "view");
        Cursor.checkLimit(limit);
        Cursor.Key c = Cursor.decode(cursor);
        List<Row> rows = jdbc.sql(SELECT + """
                         WHERE s.organization_id = :org AND s.status = 'ACTIVE'
                           AND (CAST(:q AS text) IS NULL OR s.name ILIKE '%' || CAST(:q AS text) || '%' ESCAPE '\\')
                           AND (CAST(:cname AS text) IS NULL OR (s.name, s.id) > (CAST(:cname AS text), CAST(:cid AS uuid)))
                         ORDER BY s.name, s.id LIMIT :lim
                        """)
                .param("org", actor.organizationId()).param("q", br.com.engenheiro.api.shared.web.SearchText.like(
                        br.com.engenheiro.api.shared.web.SearchText.normalize(query)))
                .param("cname", c == null ? null : c.sort()).param("cid", c == null ? null : c.id())
                .param("lim", limit + 1).query(Row.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().name(), rows.getLast().id());
        }
        return new Page(rows.stream().map(r -> view(actor, r)).toList(), next);
    }

    /** Fornecedor ativo da organização do ator (para recebimentos). */
    public boolean existsActive(Actor actor, UUID id) {
        return id != null && find(id).filter(r -> r.organizationId().equals(actor.organizationId())
                && r.status().equals("ACTIVE")).isPresent();
    }

    private static final String SELECT = """
            SELECT s.id, s.organization_id, s.name, s.contact, s.personal_document, s.status, s.version,
                   s.created_at, s.created_by
              FROM suppliers s
            """;

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(Row.class).optional();
    }

    private SupplierView view(Actor actor, Row r) {
        List<String> masked = new ArrayList<>();
        String contact = r.contact();
        if (!authz.canSeeField(actor, FieldClass.OPERATIONAL_CONTACT)) {
            contact = null;
            masked.add("contact");
        }
        String doc = r.personalDocument();
        if (!authz.canSeeField(actor, FieldClass.PERSONAL_DOCUMENT)) {
            doc = null;
            masked.add("personal_document");
        }
        return new SupplierView(r.id(), r.name(), contact, doc, r.status(), r.version(), r.createdAt(), masked);
    }

    private void rejectHidden(Actor actor, FieldClass f, String value, String name, List<FieldError> errors) {
        if (value != null && !authz.canSeeField(actor, f)) {
            errors.add(new FieldError(name, "Você não tem permissão para informar este campo"));
        }
    }
}
