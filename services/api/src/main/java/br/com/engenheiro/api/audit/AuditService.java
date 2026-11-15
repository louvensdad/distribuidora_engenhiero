package br.com.engenheiro.api.audit;

import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.RequestIds;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate independentTx;

    public AuditService(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.independentTx = new TransactionTemplate(txManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Grava dentro da transação corrente: some junto se a operação for desfeita. */
    public void record(AuditEntry e) {
        jdbc.sql("""
                        INSERT INTO audit_events (id, organization_id, branch_id, actor_user_id, actor_kind, client_id,
                                                  action, entity_type, entity_id, result, reason, changes,
                                                  request_id, origin)
                        VALUES (:id, :org, :branch, :actor, :kind, :client, :action, :etype, :eid, :result, :reason,
                                CAST(:changes AS jsonb), :rid, :origin)
                        """)
                .param("id", Ids.newId()).param("org", e.organizationId()).param("branch", e.branchId())
                .param("actor", e.actorUserId()).param("kind", e.actorKind()).param("client", e.clientId())
                .param("action", e.action()).param("etype", e.entityType()).param("eid", e.entityId())
                .param("result", e.result()).param("reason", e.reason())
                .param("changes", e.changes() == null ? null : json.writeValueAsString(e.changes()))
                .param("rid", RequestIds.current()).param("origin", e.origin())
                .update();
    }

    /** Tentativas negadas são gravadas em transação própria para não se perderem no rollback. */
    public void recordIndependently(AuditEntry e) {
        independentTx.executeWithoutResult(s -> record(e));
    }
}
