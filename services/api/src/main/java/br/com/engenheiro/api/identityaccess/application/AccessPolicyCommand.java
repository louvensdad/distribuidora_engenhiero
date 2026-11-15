package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.identityaccess.domain.Effect;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Política completa de um vínculo (substitui a anterior de forma atômica e versionada). */
public record AccessPolicyCommand(String template, List<GrantSpec> grants, List<FieldSpec> fields,
                                  Set<String> clients, String reason) {
    public record GrantSpec(String resource, String action, Scope scope, Effect effect, BigDecimal maxAmount,
                            Instant expiresAt) {
    }

    public record FieldSpec(String field, Effect effect) {
    }
}
