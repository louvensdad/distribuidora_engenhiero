package br.com.engenheiro.api.organization;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unidades operacionais (branches). Nome e fuso não são sensíveis: qualquer vínculo ativo da organização lista.
 * Criar/desativar não tem recurso no contrato de permissões: só administrador (ADR-017).
 */
@Service
public class BranchService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public BranchService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    public record BranchView(UUID id, String name, String timezone, String status, int active_people,
                             OffsetDateTime created_at) {
    }

    public record CreateCommand(String name, String timezone) {
    }

    public record StatusCommand(String status, String reason) {
    }

    @Transactional(readOnly = true)
    public List<BranchView> list(Actor actor) {
        return jdbc.sql(SELECT + " WHERE b.organization_id = :o ORDER BY b.status, b.name")
                .param("o", actor.organizationId()).query(BranchView.class).list();
    }

    @Transactional
    public BranchView create(Actor actor, CreateCommand cmd) {
        authz.requireAdmin(actor, "branches.create");
        String name = Text.trim(cmd.name());
        String tz = Text.trim(cmd.timezone()) == null ? "America/Sao_Paulo" : Text.trim(cmd.timezone());
        List<FieldError> errors = new java.util.ArrayList<>();
        if (name == null || name.length() > 120) {
            errors.add(new FieldError("name", "Nome obrigatório (até 120 caracteres)"));
        }
        try {
            ZoneId.of(tz);
        } catch (DateTimeException e) {
            errors.add(new FieldError("timezone", "Fuso inválido (ex.: America/Sao_Paulo)"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("INSERT INTO branches (id, organization_id, name, timezone, status) VALUES (:id, :o, :n, :tz, 'ACTIVE')")
                    .param("id", id).param("o", actor.organizationId()).param("n", name).param("tz", tz).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Já existe unidade com este nome.",
                    List.of(new FieldError("name", "Nome já usado")));
        }
        audit.record(new AuditEntry(actor.organizationId(), id, actor.userId(), "USER", actor.clientId(),
                "branches.create", "branch", id.toString(), "SUCCESS", null, Map.of("name", name, "timezone", tz), "api"));
        return find(id);
    }

    /** Desativar exige unidade sem pessoas ativas e sem estoque físico; reativar é livre. */
    @Transactional
    public BranchView setStatus(Actor actor, UUID id, StatusCommand cmd) {
        authz.requireAdmin(actor, "branches.edit");
        BranchView b = jdbc.sql(SELECT + " WHERE b.id = :id AND b.organization_id = :o FOR UPDATE OF b")
                .param("id", id).param("o", actor.organizationId()).query(BranchView.class).optional()
                .orElseThrow(ApiException::notFound);
        String status = cmd == null ? null : cmd.status();
        String reason = cmd == null ? null : Text.trim(cmd.reason());
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status) || reason == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe a situação (ACTIVE/INACTIVE) e o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório")));
        }
        if (status.equals("INACTIVE")) {
            int stock = jdbc.sql("SELECT count(*) FROM inventory_balances WHERE branch_id = :b AND physical > 0")
                    .param("b", id).query(Integer.class).single();
            if (b.active_people() > 0 || stock > 0) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION,
                        "A unidade ainda tem pessoas ativas ou estoque; transfira antes de desativar.");
            }
        }
        jdbc.sql("UPDATE branches SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
        Map<String, Object> ch = new HashMap<>();
        ch.put("from", b.status());
        ch.put("to", status);
        audit.record(new AuditEntry(actor.organizationId(), id, actor.userId(), "USER", actor.clientId(),
                "branches.status", "branch", id.toString(), "SUCCESS", reason, ch, "api"));
        return find(id);
    }

    private static final String SELECT = """
            SELECT b.id, b.name, b.timezone, b.status,
                   (SELECT count(*) FROM memberships m WHERE m.branch_id = b.id AND m.status = 'ACTIVE')::int AS active_people,
                   b.created_at
              FROM branches b
            """;

    private BranchView find(UUID id) {
        return jdbc.sql(SELECT + " WHERE b.id = :id").param("id", id).query(BranchView.class).single();
    }
}
