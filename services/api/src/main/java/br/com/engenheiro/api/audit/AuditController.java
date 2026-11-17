package br.com.engenheiro.api.audit;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import br.com.engenheiro.api.identityaccess.domain.AppClients;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

    /**
     * Setores da central (P03/W16). Um movimento é do setor pelo app de origem OU pelo recurso afetado — assim a ação
     * do patrão no site sobre um pedido de balcão aparece no setor de Retirada com o ator do patrão.
     */
    public enum Sector {
        COUNTER(AppClients.RETIRADA, List.of("orders", "payments", "cash", "inventory", "suppliers", "customers", "catalog")),
        DELIVERY(AppClients.ENTREGAS, List.of("trips", "vehicles", "fuel", "deliveries")),
        ACCOUNTING(AppClients.CONTABIL, List.of("taxes", "payroll", "documents", "invoices", "accounting"));

        final String client;
        final List<String> resources;

        Sector(String client, List<String> resources) {
            this.client = client;
            this.resources = resources;
        }

        /** Regex do prefixo da ação (ex.: ^(orders|payments)\.). */
        String actionPattern() {
            return "^(" + String.join("|", resources) + ")\\.";
        }
    }

    private static final ZoneId COMMERCIAL = ZoneId.of("America/Sao_Paulo");

    @GetMapping
    public Page list(Actor actor, @RequestParam(required = false) String cursor,
                     @RequestParam(required = false, defaultValue = "25") int limit,
                     @RequestParam(required = false) UUID actor_user_id,
                     @RequestParam(required = false) String client_id,
                     @RequestParam(required = false) String sector,
                     @RequestParam(required = false) String entity_type,
                     @RequestParam(required = false) String entity_id,
                     @RequestParam(required = false) LocalDate from,
                     @RequestParam(required = false) LocalDate to,
                     @RequestParam(required = false) String result) {
        authz.require(actor, "audit", "view");
        Sector sec;
        try {
            sec = sector == null || sector.isBlank() ? null : Sector.valueOf(sector);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "sector deve ser COUNTER, DELIVERY ou ACCOUNTING.");
        }
        if (entity_type != null && !entity_type.matches("[a-z_]{1,40}")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "entity_type inválido.");
        }
        if (entity_id != null && entity_id.length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "entity_id inválido.");
        }
        if (result != null && !List.of("SUCCESS", "DENIED", "FAILURE").contains(result)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "result deve ser SUCCESS, DENIED ou FAILURE.");
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Período inválido: fim exclusivo depois do início.");
        }
        // Dia civil de São Paulo: [início 00:00 SP, fim 00:00 SP).
        OffsetDateTime fromTs = from == null ? null : from.atStartOfDay(COMMERCIAL).toOffsetDateTime();
        OffsetDateTime toTs = to == null ? null : to.atStartOfDay(COMMERCIAL).toOffsetDateTime();
        if (client_id != null && !br.com.engenheiro.api.identityaccess.domain.AppClients.ALL.contains(client_id)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "client_id desconhecido.");
        }
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
                           AND (CAST(:client AS text) IS NULL OR a.client_id = CAST(:client AS text))
                           AND (CAST(:sclient AS text) IS NULL OR a.client_id = CAST(:sclient AS text)
                                OR (a.action ~ CAST(:spat AS text)
                                    AND (CAST(:sclient AS text) <> 'engenheiro-retirada'
                                         OR a.client_id IS DISTINCT FROM 'engenheiro-entregas')))
                           AND (CAST(:etype AS text) IS NULL OR a.entity_type = CAST(:etype AS text))
                           AND (CAST(:eid AS text) IS NULL OR a.entity_id = CAST(:eid AS text))
                           AND (CAST(:res AS text) IS NULL OR a.result = CAST(:res AS text))
                           AND (CAST(:fts AS timestamptz) IS NULL OR a.occurred_at >= :fts)
                           AND (CAST(:tts AS timestamptz) IS NULL OR a.occurred_at < :tts)
                           AND (CAST(:cat AS timestamptz) IS NULL OR (a.occurred_at, a.id) < (:cat, :cid))
                         ORDER BY a.occurred_at DESC, a.id DESC
                         LIMIT :lim
                        """)
                .param("org", actor.organizationId()).param("aid", actor_user_id).param("client", client_id)
                .param("sclient", sec == null ? null : sec.client).param("spat", sec == null ? null : sec.actionPattern())
                .param("etype", entity_type).param("eid", entity_id).param("res", result)
                .param("fts", fromTs).param("tts", toTs)
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
