package br.com.engenheiro.api.documents;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anexos: metadados no banco, bytes no armazenamento privado. A autorização é sempre a do registro dono
 * (o chamador verifica antes); este serviço não decide acesso sozinho. Ciclo: UPLOADING → QUARANTINED →
 * AVAILABLE | REJECTED. Só AVAILABLE é baixável.
 */
@Service
public class DocumentService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    static final String SCAN_STATE = "ESTRUTURA_VALIDADA; ANTIVIRUS_NAO_CONFIGURADO";

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final AuditService audit;

    public DocumentService(JdbcClient jdbc, ObjectStorage storage, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
    }

    public record DocumentView(UUID id, String file_name, String mime, long size_bytes, String sha256, String state,
                               String scan_state, String rejection, String created_by_name, OffsetDateTime created_at) {
        public boolean available() {
            return "AVAILABLE".equals(state);
        }
    }

    public record Stored(UUID id, UUID organizationId, String ownerType, UUID ownerId, String fileName, String mime,
                         String objectKey, String state) {
    }

    public record Content(String fileName, String mime, byte[] bytes) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView attach(Actor actor, UUID branchId, String ownerType, UUID ownerId, String originalName,
                               byte[] bytes) {
        if (!storage.configured()) {
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE,
                    "Armazenamento de anexos NÃO CONFIGURADO neste ambiente; nada foi salvo.");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Arquivo vazio ou acima de 10 MB.",
                    List.of(new FieldError("file", "Envie um arquivo de até 10 MB")));
        }
        String name = sanitize(originalName);
        
        UUID id = Ids.newId();
        String key = "quarentena/" + id;
        String sha = sha256(bytes);
        ContentInspector.Result r = ContentInspector.inspect(bytes);
        String mime = r.mime() == null ? "application/pdf" : r.mime(); // tipo desconhecido é recusado abaixo
        if (r.mime() != null) {
            name = withExtension(name, mime); // extensão segue o conteúdo real, não o que o cliente declarou
        }
        jdbc.sql("""
                        INSERT INTO documents (id, organization_id, branch_id, owner_type, owner_id, file_name, mime,
                                               size_bytes, sha256, object_key, state, scan_state, created_by)
                        VALUES (:id, :o, :b, :ot, :oid, :n, :m, :sz, :sha, :k, 'UPLOADING', 'PENDENTE', :by)
                        """)
                .param("id", id).param("o", actor.organizationId()).param("b", branchId).param("ot", ownerType)
                .param("oid", ownerId).param("n", name).param("m", mime).param("sz", (long) bytes.length)
                .param("sha", sha).param("k", key).param("by", actor.userId()).update();
        storage.put(key, bytes);
        jdbc.sql("UPDATE documents SET state = 'QUARANTINED' WHERE id = :id").param("id", id).update();
        String state;
        if (r.rejection() != null) {
            storage.delete(key); // conteúdo recusado não fica guardado
            state = "REJECTED";
        } else {
            state = "AVAILABLE";
        }
        jdbc.sql("UPDATE documents SET state = :s, scan_state = :sc, rejection = :rj WHERE id = :id")
                .param("s", state).param("sc", SCAN_STATE).param("rj", r.rejection()).param("id", id).update();
        Map<String, Object> ch = new HashMap<>();
        ch.put("owner_type", ownerType);
        ch.put("owner_id", ownerId.toString());
        ch.put("mime", mime);
        ch.put("size_bytes", bytes.length);
        ch.put("sha256", sha);
        audit.record(new AuditEntry(actor.organizationId(), branchId, actor.userId(), "USER", actor.clientId(),
                "documents.attach", "document", id.toString(), state.equals("AVAILABLE") ? "SUCCESS" : "FAILURE",
                r.rejection(), ch, "api"));
        return list(ownerType, ownerId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<DocumentView> list(String ownerType, UUID ownerId) {
        return jdbc.sql("""
                        SELECT d.id, d.file_name, d.mime, d.size_bytes, d.sha256, d.state, d.scan_state, d.rejection,
                               u.display_name AS created_by_name, d.created_at
                          FROM documents d JOIN app_users u ON u.id = d.created_by
                         WHERE d.owner_type = :t AND d.owner_id = :o ORDER BY d.created_at, d.id
                        """)
                .param("t", ownerType).param("o", ownerId).query(DocumentView.class).list();
    }

    public Optional<Stored> find(UUID id) {
        return jdbc.sql("SELECT id, organization_id, owner_type, owner_id, file_name, mime, object_key, state "
                        + "FROM documents WHERE id = :id")
                .param("id", id).query(Stored.class).optional();
    }

    /** Chamado só depois de autorizar o registro dono. O download entra no histórico. */
    @Transactional
    public Content read(Actor actor, Stored d) {
        if (!d.state().equals("AVAILABLE")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Documento indisponível (" + d.state() + ").");
        }
        byte[] bytes = storage.get(d.objectKey()).orElseThrow(() -> new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE,
                "Arquivo não encontrado no armazenamento."));
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                "documents.download", "document", d.id().toString(), "SUCCESS", null,
                Map.of("owner_type", d.ownerType(), "owner_id", d.ownerId().toString()), "api"));
        return new Content(d.fileName(), d.mime(), bytes);
    }

    static String withExtension(String name, String mime) {
        String ext = switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "application/xml" -> ".xml";
            default -> ".pdf";
        };
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(ext) || (ext.equals(".jpg") && lower.endsWith(".jpeg"))) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return (base.length() > 190 ? base.substring(0, 190) : base) + ext;
    }

    static String sanitize(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (n.isEmpty()) {
            n = "anexo";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
