package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository.MembershipRow;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Converte token válido em ator com vínculo ativo. Recarrega do banco em toda requisição: bloqueio de conta,
 * revogação de concessão ou remoção de app têm efeito na próxima chamada protegida.
 */
@Service
public class ActorResolver {
    public sealed interface Result {
        record Resolved(Actor actor) implements Result {
        }

        record Rejected(String reason, UUID userId, UUID organizationId) implements Result {
        }
    }

    private final IdentityRepository repo;
    private final Clock clock;

    public ActorResolver(IdentityRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public Result resolve(Jwt jwt, String requestedOrganization) {
        String clientId = jwt.getClaimAsString("azp");
        if (clientId == null || !AppClients.ALL.contains(clientId)) {
            return new Result.Rejected("client desconhecido", null, null);
        }
        String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
        List<MembershipRow> active = repo.findMembershipsBySubject(issuer, jwt.getSubject()).stream()
                .filter(MembershipRow::fullyActive).toList();

        Optional<MembershipRow> chosen;
        if (requestedOrganization != null && !requestedOrganization.isBlank()) {
            UUID org;
            try {
                org = UUID.fromString(requestedOrganization);
            } catch (IllegalArgumentException e) {
                return new Result.Rejected("organização inválida", null, null);
            }
            chosen = active.stream().filter(m -> m.organizationId().equals(org)).findFirst();
        } else {
            chosen = active.size() == 1 ? Optional.of(active.getFirst()) : Optional.empty();
        }
        if (chosen.isEmpty()) {
            return new Result.Rejected(active.isEmpty() ? "sem vínculo ativo" : "organização não selecionada",
                    null, null);
        }
        MembershipRow m = chosen.get();
        if (!repo.clientsOf(m.membershipId()).contains(clientId)) {
            return new Result.Rejected("aplicativo não liberado para o vínculo", m.userId(), m.organizationId());
        }
        return new Result.Resolved(new Actor(m.userId(), m.displayName(), m.membershipId(), m.version(),
                m.organizationId(), m.organizationName(), m.branchId(), m.branchName(), m.sector(),
                m.roleTemplate(), m.admin(), clientId, repo.activeGrants(m.membershipId()),
                repo.activeFieldPolicies(m.membershipId()), clock.instant()));
    }
}
