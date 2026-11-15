package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Traduz escopos concedidos em predicado SQL (filtro de linha no servidor), para listas e buscas.
 * A organização vem sempre do ator. Colunas inexistentes no recurso (null) fazem o escopo não casar nada.
 */
public final class ScopeSql {
    public record Columns(String organization, String branch, String sector, String createdBy, String assignedTo) {
    }

    public record Predicate(String sql, Map<String, Object> params) {
    }

    private ScopeSql() {
    }

    public static Predicate of(Actor actor, Set<Scope> scopes, Columns c) {
        List<String> ors = new ArrayList<>();
        for (Scope s : scopes) {
            switch (s) {
                case ORGANIZATION -> ors.add("TRUE");
                case BRANCH -> ors.add(c.branch() + " = :scope_branch");
                case SECTOR -> ors.add(c.sector() == null ? "FALSE"
                        : "(" + c.branch() + " = :scope_branch AND " + c.sector() + " = :scope_sector)");
                case OWN -> ors.add(c.createdBy() == null ? "FALSE" : c.createdBy() + " = :scope_user");
                case ASSIGNED -> ors.add(c.assignedTo() == null ? "FALSE" : c.assignedTo() + " = :scope_user");
            }
        }
        String any = ors.isEmpty() ? "FALSE" : String.join(" OR ", ors);
        return new Predicate("(" + c.organization() + " = :scope_org AND (" + any + "))", Map.of(
                "scope_org", actor.organizationId(), "scope_branch", actor.branchId(),
                "scope_sector", actor.sector().name(), "scope_user", actor.userId()));
    }
}
