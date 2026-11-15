package br.com.engenheiro.api.identityaccess.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pessoa autenticada com vínculo ativo, carregada do banco a cada requisição (revogação vale na próxima chamada).
 * A identidade nunca vem do corpo da requisição.
 */
public record Actor(UUID userId, String displayName, UUID membershipId, long policyVersion, UUID organizationId,
                    String organizationName, UUID branchId, String branchName, Sector sector, String roleTemplate,
                    boolean admin, String clientId, List<Grant> grants, Map<FieldClass, Effect> fieldPolicies,
                    Instant loadedAt) {
}
