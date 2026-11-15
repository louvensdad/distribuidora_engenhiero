package br.com.engenheiro.api.audit;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Histórico com ator real. Paginação por cursor (padrão 25, máximo 100), sempre filtrado pela organização. */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;

    public AuditController(JdbcClient jdbc, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    public record AuditView(UUID id, OffsetDateTime occurred_at, UUID actor_user_id, String actor_name,
                            String actor_kind, String client_id, String action, String entity_type,
                            String entity_id, String result, String reason, String request_id) {
    }

    public record Page(List<AuditView> items, String next_cursor) {
    }

    @GetMapping
    public Page list(Actor actor, @RequestParam(required = false) String cursor,
                     @RequestParam(required = false, defaultValue = "25") int limit,
                     @RequestParam(required = false) UUID actor_user_id) {
        authz.require(actor, "audit", "view");
        if (limit < 1 || limit > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit deve estar entre 1 e 100.");
        }
        OffsetDateTime cAt = null;
        UUID cId = null;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                cAt = OffsetDateTime.parse(parts[0]);
                cId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor inválido.");
            }
        }
        List<AuditView> rows = jdbc.sql("""
                        SELECT a.id, a.occurred_at, a.actor_user_id, u.display_name AS actor_name, a.actor_kind,
                               a.client_id, a.action, a.entity_type, a.entity_id, a.result, a.reason, a.request_id
                          FROM audit_events a
                          LEFT JOIN app_users u ON u.id = a.actor_user_id
                         WHERE a.organization_id = :org
                           AND (CAST(:aid AS uuid) IS NULL OR a.actor_user_id = :aid)
                           AND (CAST(:cat AS timestamptz) IS NULL OR (a.occurred_at, a.id) < (:cat, :cid))
                         ORDER BY a.occurred_at DESC, a.id DESC
                         LIMIT :lim
                        """)
                .param("org", actor.organizationId()).param("aid", actor_user_id)
                .param("cat", cAt).param("cid", cId).param("lim", limit + 1)
                .query(AuditView.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            AuditView last = rows.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.occurred_at().toString() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return new Page(rows, next);
    }
}
