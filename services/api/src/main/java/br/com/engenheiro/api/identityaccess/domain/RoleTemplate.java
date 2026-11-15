package br.com.engenheiro.api.identityaccess.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modelos iniciais (cerebro/04). São só ponto de partida: aplicar um modelo grava concessões editáveis.
 * Capacidades de confirmar pagamento, caixa, atribuir viagem, aprovar etc. ficam fora: o dono concede.
 */
public enum RoleTemplate {
    ADMIN(Sector.ADMINISTRACAO, true, Set.of(AppClients.WEB, AppClients.GESTAO), List.of(), Map.of()),

    BALCAO(Sector.RETIRADA, false, Set.of(AppClients.RETIRADA), List.of(
            g("orders.pickup", "view", Scope.OWN),
            g("orders.pickup", "create", Scope.OWN),
            g("orders.pickup", "edit_draft", Scope.OWN),
            g("catalog", "view", Scope.ORGANIZATION),
            g("customers", "view", Scope.BRANCH),
            g("customers", "create", Scope.BRANCH),
            g("inventory.stock", "view", Scope.BRANCH)),
            Map.of(FieldClass.OPERATIONAL_CONTACT, Effect.ALLOW)),

    RECEBIMENTO(Sector.RETIRADA, false, Set.of(AppClients.RETIRADA), List.of(
            g("suppliers", "view", Scope.ORGANIZATION),
            g("inventory.receipts", "view", Scope.BRANCH),
            g("inventory.receipts", "create", Scope.OWN),
            g("catalog", "view", Scope.ORGANIZATION),
            g("inventory.stock", "view", Scope.BRANCH)),
            Map.of()),

    COORDENADOR_ENTREGAS(Sector.ENTREGAS, false, Set.of(AppClients.ENTREGAS), List.of(
            g("orders.delivery", "view", Scope.SECTOR),
            g("orders.delivery", "create", Scope.SECTOR),
            g("orders.delivery", "edit_draft", Scope.SECTOR),
            g("trips", "view", Scope.SECTOR),
            g("trips", "create", Scope.SECTOR),
            g("vehicles", "view", Scope.BRANCH),
            g("customers", "view", Scope.BRANCH),
            g("customers", "create", Scope.BRANCH),
            g("catalog", "view", Scope.ORGANIZATION)),
            Map.of(FieldClass.OPERATIONAL_CONTACT, Effect.ALLOW, FieldClass.DELIVERY_ADDRESS, Effect.ALLOW)),

    MOTORISTA(Sector.ENTREGAS, false, Set.of(AppClients.ENTREGAS), List.of(
            g("trips", "view", Scope.ASSIGNED),
            g("trips", "confirm", Scope.ASSIGNED),
            g("vehicles", "view", Scope.ASSIGNED),
            g("fuel", "create", Scope.ASSIGNED),
            g("fuel", "view", Scope.OWN)),
            Map.of(FieldClass.OPERATIONAL_CONTACT, Effect.ALLOW, FieldClass.DELIVERY_ADDRESS, Effect.ALLOW)),

    CONTADOR(Sector.CONTABILIDADE, false, Set.of(AppClients.CONTABIL), List.of(
            g("taxes", "view", Scope.ORGANIZATION),
            g("taxes", "create", Scope.ORGANIZATION),
            g("taxes", "request", Scope.ORGANIZATION),
            g("payroll", "view", Scope.ORGANIZATION),
            g("payroll", "create", Scope.ORGANIZATION),
            g("payroll", "request", Scope.ORGANIZATION),
            g("invoices", "view", Scope.ORGANIZATION),
            g("invoices", "create", Scope.ORGANIZATION),
            g("invoices", "request", Scope.ORGANIZATION),
            g("documents", "view", Scope.ORGANIZATION),
            g("documents", "create", Scope.ORGANIZATION),
            g("documents", "attach", Scope.ORGANIZATION)),
            Map.of());

    public record TemplateGrant(String resource, String action, Scope scope) {
    }

    private final Sector sector;
    private final boolean admin;
    private final Set<String> clients;
    private final List<TemplateGrant> grants;
    private final Map<FieldClass, Effect> fields;

    RoleTemplate(Sector sector, boolean admin, Set<String> clients, List<TemplateGrant> grants,
                 Map<FieldClass, Effect> fields) {
        this.sector = sector;
        this.admin = admin;
        this.clients = clients;
        this.grants = grants;
        this.fields = fields;
    }

    private static TemplateGrant g(String resource, String action, Scope scope) {
        return new TemplateGrant(resource, action, scope);
    }

    public Sector sector() {
        return sector;
    }

    public boolean admin() {
        return admin;
    }

    public Set<String> clients() {
        return clients;
    }

    public List<TemplateGrant> grants() {
        return grants;
    }

    public Map<FieldClass, Effect> fields() {
        return fields;
    }
}
