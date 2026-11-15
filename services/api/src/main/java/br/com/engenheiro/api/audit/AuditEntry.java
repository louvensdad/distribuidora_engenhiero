package br.com.engenheiro.api.audit;

import java.util.Map;
import java.util.UUID;

/** Entrada de auditoria. `changes` deve conter apenas campos permitidos/mascarados — nunca tokens ou senhas. */
public record AuditEntry(UUID organizationId, UUID branchId, UUID actorUserId, String actorKind, String clientId,
                         String action, String entityType, String entityId, String result, String reason,
                         Map<String, Object> changes, String origin) {
}
