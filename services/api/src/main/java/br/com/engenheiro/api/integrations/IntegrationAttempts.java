package br.com.engenheiro.api.integrations;

import br.com.engenheiro.api.shared.ids.Ids;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Histórico de cada tentativa com integração externa, gravado em transação própria: fica registrado mesmo quando a
 * operação principal é recusada (ex.: BLOQUEADO por falta de provedor) ou desfeita.
 */
@Component
public class IntegrationAttempts {
    private final JdbcClient jdbc;

    public IntegrationAttempts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AttemptView(UUID id, String integration, String operation, String entity_type, UUID entity_id,
                              String outcome, String detail, String actor_name, OffsetDateTime started_at,
                              OffsetDateTime finished_at) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID start(UUID org, String integration, String operation, String entityType, UUID entityId, String key,
                      UUID actorId) {
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO integration_attempts (id, organization_id, integration, operation, entity_type, entity_id,
                                                          idempotency_key, outcome, actor_id)
                        VALUES (:id, :o, :i, :op, :et, :e, :k, 'SENT', :a)
                        """)
                .param("id", id).param("o", org).param("i", integration).param("op", operation).param("et", entityType)
                .param("e", entityId).param("k", key).param("a", actorId).update();
        return id;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID id, String outcome, String detail) {
        jdbc.sql("UPDATE integration_attempts SET outcome = :out, detail = :d, finished_at = clock_timestamp() WHERE id = :id")
                .param("out", outcome).param("d", cut(detail)).param("id", id).update();
    }

    /** Tentativa recusada antes de sair do sistema (provedor ausente, configuração incompleta). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blocked(UUID org, String integration, String operation, String entityType, UUID entityId, String key,
                        UUID actorId, String detail) {
        UUID id = start(org, integration, operation, entityType, entityId, key, actorId);
        finish(id, "BLOCKED", detail);
    }

    @Transactional(readOnly = true)
    public Optional<String> lastKey(String entityType, UUID entityId, String operation) {
        return jdbc.sql("""
                        SELECT idempotency_key FROM integration_attempts
                         WHERE entity_type = :et AND entity_id = :e AND operation = :op AND outcome <> 'BLOCKED'
                         ORDER BY started_at DESC LIMIT 1
                        """)
                .param("et", entityType).param("e", entityId).param("op", operation).query(String.class).optional();
    }

    @Transactional(readOnly = true)
    public List<AttemptView> forEntity(String entityType, UUID entityId) {
        return jdbc.sql(SELECT + " WHERE a.entity_type = :et AND a.entity_id = :e ORDER BY a.started_at")
                .param("et", entityType).param("e", entityId).query(AttemptView.class).list();
    }

    @Transactional(readOnly = true)
    public List<AttemptView> recent(UUID org, String integration, int limit) {
        return jdbc.sql(SELECT + " WHERE a.organization_id = :o AND (CAST(:i AS text) IS NULL OR a.integration = :i)"
                        + " ORDER BY a.started_at DESC LIMIT :l")
                .param("o", org).param("i", integration).param("l", limit).query(AttemptView.class).list();
    }

    private static final String SELECT = """
            SELECT a.id, a.integration, a.operation, a.entity_type, a.entity_id, a.outcome, a.detail,
                   u.display_name AS actor_name, a.started_at, a.finished_at
              FROM integration_attempts a LEFT JOIN app_users u ON u.id = a.actor_id
            """;

    private static String cut(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
