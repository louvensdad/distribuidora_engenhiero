package br.com.engenheiro.api.identityaccess.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Grant(UUID id, String resource, String action, Scope scope, Effect effect, BigDecimal maxAmount,
                    Instant expiresAt) {

    public boolean isActiveAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public boolean matches(String resource, String action) {
        return this.resource.equals(resource) && this.action.equals(action);
    }
}
