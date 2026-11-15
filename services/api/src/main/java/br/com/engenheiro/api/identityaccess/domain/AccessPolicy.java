package br.com.engenheiro.api.identityaccess.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Regras puras de decisão (sem I/O). Ordem: catálogo -> negação explícita -> administrador -> concessões ativas.
 * Negação explícita vence qualquer permissão, inclusive de administrador.
 */
public final class AccessPolicy {
    private AccessPolicy() {
    }

    public record Decision(boolean allowed, Set<Scope> scopes, Optional<BigDecimal> maxAmount) {
        static Decision deny() {
            return new Decision(false, Set.of(), Optional.empty());
        }
    }

    public static Decision decide(CapabilityCatalog catalog, Actor actor, String resource, String action,
                                  Instant now) {
        if (!catalog.contains(resource, action)) {
            return Decision.deny();
        }
        boolean explicitDeny = actor.grants().stream()
                .anyMatch(g -> g.effect() == Effect.DENY && g.matches(resource, action) && g.isActiveAt(now));
        if (explicitDeny) {
            return Decision.deny();
        }
        if (actor.admin()) {
            return new Decision(true, EnumSet.of(Scope.ORGANIZATION), Optional.empty());
        }
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        BigDecimal max = null;
        boolean unlimited = false;
        for (Grant g : actor.grants()) {
            if (g.effect() == Effect.ALLOW && g.matches(resource, action) && g.isActiveAt(now)) {
                scopes.add(g.scope());
                if (g.maxAmount() == null) {
                    unlimited = true;
                } else if (max == null || g.maxAmount().compareTo(max) > 0) {
                    max = g.maxAmount();
                }
            }
        }
        if (scopes.isEmpty()) {
            return Decision.deny();
        }
        return new Decision(true, scopes, unlimited ? Optional.empty() : Optional.of(max));
    }

    /** O registro está dentro de algum escopo concedido? Organização diferente nunca passa. */
    public static boolean recordInScope(Actor actor, Set<Scope> scopes, RecordRef record) {
        if (!Objects.equals(actor.organizationId(), record.organizationId())) {
            return false;
        }
        for (Scope scope : scopes) {
            boolean ok = switch (scope) {
                case ORGANIZATION -> true;
                case BRANCH -> Objects.equals(actor.branchId(), record.branchId());
                case SECTOR -> Objects.equals(actor.branchId(), record.branchId())
                        && actor.sector() == record.sector();
                case ASSIGNED -> record.assignedTo() != null && record.assignedTo().equals(actor.userId());
                case OWN -> record.createdBy() != null && record.createdBy().equals(actor.userId());
            };
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** Campo sensível: administrador vê, salvo negação explícita; demais só com ALLOW explícito. */
    public static boolean canSeeField(Actor actor, FieldClass field) {
        Effect effect = actor.fieldPolicies().get(field);
        if (effect == Effect.DENY) {
            return false;
        }
        return actor.admin() || effect == Effect.ALLOW;
    }
}
