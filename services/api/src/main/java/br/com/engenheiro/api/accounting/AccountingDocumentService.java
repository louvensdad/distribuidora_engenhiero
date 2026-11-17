package br.com.engenheiro.api.accounting;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.documents.DocumentService;
import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central de documentos (CONT-04): documento lógico com versões de arquivos privados (bytes reais validados pelo
 * servidor — tipo pelo conteúdo, tamanho, hash; PDF ativo recusado), solicitações de documento e download só para
 * quem pode ver (`documents.view`), registrado no histórico. Substituir mantém a versão anterior, com motivo e autor.
 * Enviar: `documents.attach`; solicitar: `documents.create`.
 */
@Service
public class AccountingDocumentService {
    static final List<String> KINDS = List.of("GUIA", "COMPROVANTE", "FOLHA", "NOTA_FISCAL", "CONTRATO", "EXTRATO", "OUTRO");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final DocumentService documents;

    public AccountingDocumentService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, DocumentService documents) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.documents = documents;
    }

    public record VersionView(int version_no, String reason, String created_by_name, OffsetDateTime created_at,
                              DocumentView file) {
    }

    public record DocView(UUID id, String kind, String title, String competence, String related_type, UUID related_id,
                          int current_version, String state, String created_by_name, OffsetDateTime created_at,
                          List<VersionView> versions, long version) {
    }

    public record RequestCommand(String title, String description, String kind, String competence, UUID requested_from,
                                 LocalDate due_date) {
    }

    public record RequestView(UUID id, String title, String description, String kind, String competence,
                              String requested_from_name, LocalDate due_date, String state, UUID fulfilled_document_id,
                              String fulfilled_by_name, OffsetDateTime fulfilled_at, String created_by_name,
                              OffsetDateTime created_at, long version) {
    }

    // ---------------- envio (bytes reais) ----------------
    @Transactional
    public DocView upload(Actor actor, String kind, String title, String competence, UUID requestId, String fileName,
                          byte[] bytes) {
        authz.require(actor, "documents", "attach");
        List<FieldError> errors = new ArrayList<>();
        if (kind == null || !KINDS.contains(kind)) {
            errors.add(new FieldError("kind", "Escolha o tipo de documento"));
        }
        String t = Text.trim(title);
        if (t == null || t.length() > 160) {
            errors.add(new FieldError("title", "Informe o título (até 160 caracteres)"));
        }
        LocalDate comp = month(competence, errors);
        if (bytes == null || bytes.length == 0) {
            errors.add(new FieldError("file", "Envie o arquivo (só o nome não basta)"));
        }
        RequestView req = null;
        if (requestId != null) {
            req = request(actor.organizationId(), requestId);
            if (req == null || !req.state().equals("OPEN")) {
                errors.add(new FieldError("request_id", "Solicitação não encontrada ou já atendida"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO accounting_documents (id, organization_id, branch_id, kind, title, competence, created_by)
                        VALUES (:id, :o, :b, :k, :t, :c, :by)
                        """).param("id", id).param("o", actor.organizationId()).param("b", actor.branchId()).param("k", kind)
                .param("t", t).param("c", comp).param("by", actor.userId()).update();
        addVersion(actor, id, fileName, bytes, null);
        if (req != null) {
            jdbc.sql("""
                            UPDATE document_requests SET state = 'FULFILLED', fulfilled_document_id = :d, fulfilled_by = :by,
                                   fulfilled_at = now(), version = version + 1 WHERE id = :id AND state = 'OPEN'
                            """).param("d", id).param("by", actor.userId()).param("id", requestId).update();
            audit.record(entry(actor, "documents.request_fulfilled", "document_request", requestId, Map.of("document", id.toString())));
        }
        audit.record(entry(actor, "documents.upload", "accounting_document", id, Map.of("kind", kind, "title", t)));
        return view(actor.organizationId(), id);
    }

    /** Nova versão: a anterior fica guardada; motivo obrigatório. */
    @Transactional
    public DocView newVersion(Actor actor, UUID id, String reason, String fileName, byte[] bytes) {
        authz.require(actor, "documents", "attach");
        lock(actor.organizationId(), id);
        String r = Text.trim(reason);
        if (r == null || r.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo da nova versão.",
                    List.of(new FieldError("reason", "Motivo obrigatório")));
        }
        addVersion(actor, id, fileName, bytes, r);
        audit.record(entry(actor, "documents.new_version", "accounting_document", id, Map.of("reason", r)));
        return view(actor.organizationId(), id);
    }

    private void addVersion(Actor actor, UUID docId, String fileName, byte[] bytes, String reason) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Envie o arquivo.", List.of(new FieldError("file", "Obrigatório")));
        }
        UUID branch = jdbc.sql("SELECT branch_id FROM accounting_documents WHERE id = :id").param("id", docId).query(UUID.class).single();
        DocumentView file = documents.attach(actor, branch, "accounting_document", docId, fileName, bytes);
        if (!file.available()) {
            // Conteúdo recusado pela validação real (tipo, PDF ativo, assinatura): não vira versão.
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Arquivo recusado: " + file.rejection(),
                    List.of(new FieldError("file", file.rejection() == null ? "Conteúdo inválido" : file.rejection())));
        }
        int next = jdbc.sql("UPDATE accounting_documents SET current_version = current_version + 1, version = version + 1 "
                + "WHERE id = :id RETURNING current_version").param("id", docId).query(Integer.class).single();
        jdbc.sql("""
                        INSERT INTO accounting_document_versions (id, document_id, version_no, file_id, reason, created_by)
                        VALUES (:id, :d, :n, :f, :r, :by)
                        """).param("id", Ids.newId()).param("d", docId).param("n", next).param("f", file.id())
                .param("r", reason).param("by", actor.userId()).update();
    }

    // ---------------- consulta ----------------
    @Transactional(readOnly = true)
    public List<DocView> list(Actor actor, String kind, String competence) {
        authz.require(actor, "documents", "view");
        List<FieldError> errors = new ArrayList<>();
        LocalDate comp = month(competence, errors);
        if (!errors.isEmpty() || (kind != null && !KINDS.contains(kind))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Filtro inválido.");
        }
        List<UUID> ids = jdbc.sql("""
                        SELECT id FROM accounting_documents WHERE organization_id = :o AND state = 'ACTIVE'
                           AND (CAST(:k AS text) IS NULL OR kind = CAST(:k AS text))
                           AND (CAST(:c AS date) IS NULL OR competence = CAST(:c AS date))
                         ORDER BY created_at DESC LIMIT 200
                        """).param("o", actor.organizationId()).param("k", kind).param("c", comp).query(UUID.class).list();
        return ids.stream().map(i -> view(actor.organizationId(), i)).toList();
    }

    @Transactional(readOnly = true)
    public DocView get(Actor actor, UUID id) {
        authz.require(actor, "documents", "view");
        return view(actor.organizationId(), id);
    }

    /** Download de versão: quem vê documentos; o próprio download fica no histórico (DocumentService). */
    @Transactional(readOnly = true)
    public void requireVisible(Actor actor, UUID docId) {
        authz.require(actor, "documents", "view");
        view(actor.organizationId(), docId);
    }

    // ---------------- solicitações ----------------
    @Transactional
    public RequestView requestDocument(Actor actor, RequestCommand cmd) {
        authz.require(actor, "documents", "create");
        List<FieldError> errors = new ArrayList<>();
        String t = Text.trim(cmd == null ? null : cmd.title());
        if (t == null || t.length() > 160) {
            errors.add(new FieldError("title", "Informe o que precisa"));
        }
        String d = Text.trim(cmd == null ? null : cmd.description());
        if (d != null && d.length() > 500) {
            errors.add(new FieldError("description", "Até 500 caracteres"));
        }
        String kind = cmd == null || cmd.kind() == null ? "OUTRO" : cmd.kind();
        if (!KINDS.contains(kind)) {
            errors.add(new FieldError("kind", "Tipo inválido"));
        }
        LocalDate comp = month(cmd == null ? null : cmd.competence(), errors);
        if (cmd != null && cmd.requested_from() != null && jdbc.sql("""
                        SELECT count(*) FROM memberships WHERE user_id = :u AND organization_id = :o AND status = 'ACTIVE'
                        """).param("u", cmd.requested_from()).param("o", actor.organizationId()).query(Integer.class).single() == 0) {
            errors.add(new FieldError("requested_from", "Pessoa não encontrada"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO document_requests (id, organization_id, title, description, kind, competence, requested_from,
                                                       due_date, created_by)
                        VALUES (:id, :o, :t, :d, :k, :c, :rf, :due, :by)
                        """).param("id", id).param("o", actor.organizationId()).param("t", t).param("d", d).param("k", kind)
                .param("c", comp).param("rf", cmd.requested_from()).param("due", cmd.due_date()).param("by", actor.userId()).update();
        audit.record(entry(actor, "documents.request", "document_request", id, Map.of("title", t)));
        return request(actor.organizationId(), id);
    }

    @Transactional(readOnly = true)
    public List<RequestView> requests(Actor actor, String state) {
        authz.require(actor, "documents", "view");
        return jdbc.sql(REQ_SELECT + " WHERE r.organization_id = :o AND (CAST(:s AS text) IS NULL OR r.state = CAST(:s AS text))"
                + " ORDER BY r.created_at DESC LIMIT 200").param("o", actor.organizationId()).param("s", state)
                .query(RequestView.class).list();
    }

    @Transactional
    public RequestView cancelRequest(Actor actor, UUID id) {
        authz.require(actor, "documents", "create");
        int n = jdbc.sql("UPDATE document_requests SET state = 'CANCELLED', version = version + 1 WHERE id = :id AND organization_id = :o AND state = 'OPEN'")
                .param("id", id).param("o", actor.organizationId()).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Solicitação não está aberta.");
        }
        audit.record(entry(actor, "documents.request_cancel", "document_request", id, Map.of()));
        return request(actor.organizationId(), id);
    }

    // ---------------- apoio ----------------
    static final String REQ_SELECT = """
            SELECT r.id, r.title, r.description, r.kind, to_char(r.competence, 'YYYY-MM') AS competence,
                   rf.display_name AS requested_from_name, r.due_date, r.state, r.fulfilled_document_id,
                   fb.display_name AS fulfilled_by_name, r.fulfilled_at, c.display_name AS created_by_name, r.created_at, r.version
              FROM document_requests r JOIN app_users c ON c.id = r.created_by LEFT JOIN app_users rf ON rf.id = r.requested_from
              LEFT JOIN app_users fb ON fb.id = r.fulfilled_by
            """;

    private RequestView request(UUID org, UUID id) {
        return jdbc.sql(REQ_SELECT + " WHERE r.id = :id AND r.organization_id = :o").param("id", id).param("o", org)
                .query(RequestView.class).optional().orElse(null);
    }

    private void lock(UUID org, UUID id) {
        jdbc.sql("SELECT id FROM accounting_documents WHERE id = :id AND organization_id = :o FOR UPDATE").param("id", id)
                .param("o", org).query(UUID.class).optional().orElseThrow(ApiException::notFound);
    }

    private DocView view(UUID org, UUID id) {
        record D(UUID id, String kind, String title, LocalDate competence, String relatedType, UUID relatedId, int currentVersion,
                 String state, String createdByName, OffsetDateTime createdAt, long version) {
        }
        D d = jdbc.sql("""
                        SELECT a.id, a.kind, a.title, a.competence, a.related_type, a.related_id, a.current_version, a.state,
                               u.display_name AS created_by_name, a.created_at, a.version
                          FROM accounting_documents a JOIN app_users u ON u.id = a.created_by WHERE a.id = :id AND a.organization_id = :o
                        """).param("id", id).param("o", org).query(D.class).optional().orElseThrow(ApiException::notFound);
        Map<UUID, DocumentView> files = new HashMap<>();
        documents.list("accounting_document", id).forEach(f -> files.put(f.id(), f));
        record V(int versionNo, UUID fileId, String reason, String createdByName, OffsetDateTime createdAt) {
        }
        List<VersionView> versions = jdbc.sql("""
                        SELECT v.version_no, v.file_id, v.reason, u.display_name AS created_by_name, v.created_at
                          FROM accounting_document_versions v JOIN app_users u ON u.id = v.created_by
                         WHERE v.document_id = :d ORDER BY v.version_no DESC
                        """).param("d", id).query(V.class).list().stream()
                .map(v -> new VersionView(v.versionNo(), v.reason(), v.createdByName(), v.createdAt(), files.get(v.fileId()))).toList();
        return new DocView(d.id(), d.kind(), d.title(), d.competence() == null ? null : YearMonth.from(d.competence()).toString(),
                d.relatedType(), d.relatedId(), d.currentVersion(), d.state(), d.createdByName(), d.createdAt(), versions, d.version());
    }

    private static LocalDate month(String raw, List<FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(raw.trim()).atDay(1);
        } catch (DateTimeParseException e) {
            errors.add(new FieldError("competence", "Competência no formato AAAA-MM"));
            return null;
        }
    }

    static AuditEntry entry(Actor actor, String action, String type, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                type, id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
