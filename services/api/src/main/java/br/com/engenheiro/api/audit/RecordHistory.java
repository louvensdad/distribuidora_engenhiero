package br.com.engenheiro.api.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Histórico de UM registro para quem já pode ver o registro (C04: "Histórico da decisão"). Mostra só quem fez, quando,
 * por qual aplicativo e a observação escrita — nunca os demais campos de `changes` (valores, conta bancária, pessoas).
 * A autorização é do chamador: este componente não decide quem vê.
 */
@Component
public class RecordHistory {
    private final JdbcClient jdbc;

    public RecordHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(OffsetDateTime occurred_at, String action, String actor_name, String actor_kind, String client_id,
                       String note) {
    }

    public List<Item> of(UUID organizationId, String entityType, UUID entityId) {
        return jdbc.sql("""
                        SELECT a.occurred_at, a.action, u.display_name AS actor_name, a.actor_kind, a.client_id,
                               coalesce(a.changes ->> 'reason', a.changes ->> 'note') AS note
                          FROM audit_events a LEFT JOIN app_users u ON u.id = a.actor_user_id
                         WHERE a.organization_id = :o AND a.entity_type = :t AND a.entity_id = :id AND a.result = 'SUCCESS'
                         ORDER BY a.occurred_at, a.id
                         LIMIT 200
                        """).param("o", organizationId).param("t", entityType).param("id", entityId.toString())
                .query(Item.class).list();
    }
}
