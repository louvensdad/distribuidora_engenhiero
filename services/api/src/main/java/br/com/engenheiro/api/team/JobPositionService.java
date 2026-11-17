package br.com.engenheiro.api.team;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cargos (ADR-027): nome, setor, modelo de acesso sugerido e descrição. Ver: users.view; manter: users.edit_draft. */
@Service
public class JobPositionService {
    static final List<String> SECTORS = List.of("ADMINISTRACAO", "RETIRADA", "ENTREGAS", "CONTABILIDADE");
    static final List<String> TEMPLATES = List.of("BALCAO", "RECEBIMENTO", "COORDENADOR_ENTREGAS", "MOTORISTA", "CONTADOR");

    public record PositionView(UUID id, String name, String sector, String suggested_template, String description,
                               String status, long employees, long version, OffsetDateTime created_at) {
    }

    public record Command(String name, String sector, String suggested_template, String description, String status) {
    }

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public JobPositionService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT p.id, p.name, p.sector, p.suggested_template, p.description, p.status,
                   (SELECT count(*) FROM employees e WHERE e.job_position_id = p.id AND e.status <> 'TERMINATED') AS employees,
                   p.version, p.created_at
              FROM job_positions p
            """;

    @Transactional(readOnly = true)
    public List<PositionView> list(Actor actor, boolean includeArchived) {
        authz.require(actor, "users", "view");
        return jdbc.sql(SELECT + " WHERE p.organization_id = :o AND (CAST(:all AS boolean) OR p.status = 'ACTIVE') ORDER BY p.status, lower(p.name)")
                .param("o", actor.organizationId()).param("all", includeArchived).query(PositionView.class).list();
    }

    PositionView find(Actor actor, UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id AND p.organization_id = :o").param("id", id)
                .param("o", actor.organizationId()).query(PositionView.class).optional().orElseThrow(ApiException::notFound);
    }

    private record Clean(String name, String sector, String template, String description, String status) {
    }

    private static Clean validate(Command c, String currentStatus) {
        List<FieldError> errors = new ArrayList<>();
        String name = c == null || c.name() == null ? null : c.name().strip().replaceAll("\\s+", " ");
        if (name == null || name.isEmpty() || name.length() > 80) {
            errors.add(new FieldError("name", "Nome do cargo obrigatório (até 80 caracteres)"));
        }
        String sector = c == null ? null : c.sector();
        if (sector == null || !SECTORS.contains(sector)) {
            errors.add(new FieldError("sector", "Setor: ADMINISTRACAO, RETIRADA, ENTREGAS ou CONTABILIDADE"));
        }
        String tpl = c == null || c.suggested_template() == null || c.suggested_template().isBlank() ? null : c.suggested_template();
        if (tpl != null && !TEMPLATES.contains(tpl)) {
            errors.add(new FieldError("suggested_template", "Modelo de acesso inválido (administrador não é sugerível)"));
        }
        String desc = c == null || c.description() == null || c.description().isBlank() ? null : c.description().strip();
        if (desc != null && desc.length() > 500) {
            errors.add(new FieldError("description", "Até 500 caracteres"));
        }
        String status = c == null || c.status() == null ? currentStatus : c.status();
        if (!List.of("ACTIVE", "ARCHIVED").contains(status)) {
            errors.add(new FieldError("status", "ACTIVE ou ARCHIVED"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        return new Clean(name, sector, tpl, desc, status);
    }

    private static ApiException duplicate() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Já existe um cargo com esse nome.",
                List.of(new FieldError("name", "Já existe um cargo com esse nome")));
    }

    @Transactional
    public PositionView create(Actor actor, Command cmd) {
        authz.require(actor, "users", "edit_draft");
        Clean c = validate(cmd, "ACTIVE");
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO job_positions (id, organization_id, name, sector, suggested_template, description, created_by)
                            VALUES (:id, :o, :n, :s, :t, :d, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("n", c.name()).param("s", c.sector())
                    .param("t", c.template()).param("d", c.description()).param("by", actor.userId()).update();
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                "job_positions.create", "job_position", id.toString(), "SUCCESS", null, Map.of("name", c.name(), "sector", c.sector()), "api"));
        return find(actor, id);
    }

    @Transactional
    public PositionView update(Actor actor, UUID id, long expectedVersion, Command cmd) {
        authz.require(actor, "users", "edit_draft");
        PositionView before = find(actor, id);
        Clean c = validate(cmd, before.status());
        if (c.status().equals("ARCHIVED") && before.employees() > 0 && !before.status().equals("ARCHIVED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "Há " + before.employees() + " funcionário(s) ativo(s) neste cargo. Mude o cargo deles antes de arquivar.");
        }
        int n;
        try {
            n = jdbc.sql("""
                            UPDATE job_positions SET name = :n, sector = :s, suggested_template = :t, description = :d, status = :st,
                                   version = version + 1, updated_at = now(), updated_by = :by
                             WHERE id = :id AND version = :v
                            """).param("n", c.name()).param("s", c.sector()).param("t", c.template()).param("d", c.description())
                    .param("st", c.status()).param("by", actor.userId()).param("id", id).param("v", expectedVersion).update();
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O cargo foi alterado por outra pessoa. Recarregue.");
        }
        List<String> changed = new ArrayList<>();
        if (!c.name().equals(before.name())) {
            changed.add("name");
        }
        if (!c.sector().equals(before.sector())) {
            changed.add("sector");
        }
        if (!Objects.equals(c.template(), before.suggested_template())) {
            changed.add("suggested_template");
        }
        if (!Objects.equals(c.description(), before.description())) {
            changed.add("description");
        }
        if (!c.status().equals(before.status())) {
            changed.add("status");
        }
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                c.status().equals("ARCHIVED") && changed.contains("status") ? "job_positions.archive" : "job_positions.update",
                "job_position", id.toString(), "SUCCESS", null, Map.of("fields_changed", changed), "api"));
        return find(actor, id);
    }
}
