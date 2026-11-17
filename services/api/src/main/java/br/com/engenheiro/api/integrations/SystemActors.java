package br.com.engenheiro.api.integrations;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Identidade técnica das integrações (V11): não faz login, não tem permissões; só aparece como autor de lançamentos
 * originados por um aviso assinado do provedor ou pela consulta automática de resultado incerto.
 */
public final class SystemActors {
    public static final UUID INTEGRATIONS_USER = UUID.fromString("00000000-0000-4000-8000-00000000f008");

    private SystemActors() {
    }

    public static Actor integration(UUID organizationId, UUID branchId, String clientId) {
        return new Actor(INTEGRATIONS_USER, "Integração — provedor externo", null, 0, organizationId, null, branchId,
                null, null, null, false, clientId, List.of(), Map.of(), Instant.now());
    }
}
