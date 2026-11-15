package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AccessPolicy;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.CapabilityCatalog;
import br.com.engenheiro.api.identityaccess.domain.Effect;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.Grant;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regras puras de decisão: escopo de registro, negação explícita, validade, alçada e campos. */
class AccessPolicyTest {
    static final CapabilityCatalog CATALOG = new CapabilityCatalog(Map.of(
            "trips", Set.of("view", "confirm"),
            "dashboard", Set.of("view"),
            "expenses", Set.of("approve"),
            "orders.pickup", Set.of("view")));
    static final UUID ORG = UUID.randomUUID();
    static final UUID OTHER_ORG = UUID.randomUUID();
    static final UUID BRANCH = UUID.randomUUID();
    static final UUID OTHER_BRANCH = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    static Actor actor(boolean admin, Sector sector, List<Grant> grants, Map<FieldClass, Effect> fields) {
        return new Actor(UUID.randomUUID(), "Teste", UUID.randomUUID(), 1, ORG, "Org", BRANCH, "Matriz", sector,
                "X", admin, "engenheiro-entregas", grants, fields, NOW);
    }

    static Grant allow(String r, String a, Scope s) {
        return new Grant(UUID.randomUUID(), r, a, s, Effect.ALLOW, null, null);
    }

    @Test
    void semConcessaoNega() {
        Actor a = actor(false, Sector.RETIRADA, List.of(), Map.of());
        assertThat(AccessPolicy.decide(CATALOG, a, "dashboard", "view", NOW).allowed()).isFalse();
    }

    @Test
    void capacidadeForaDoCatalogoNegaAteParaAdministrador() {
        Actor admin = actor(true, Sector.ADMINISTRACAO, List.of(), Map.of());
        assertThat(AccessPolicy.decide(CATALOG, admin, "integration.secret", "read", NOW).allowed()).isFalse();
    }

    /** SEC-07 / S03: motorista A não acessa viagem do motorista B mesmo sabendo o ID. */
    @Test
    void motoristaSoVeViagemAtribuidaASi() {
        Actor motoristaA = actor(false, Sector.ENTREGAS, List.of(allow("trips", "view", Scope.ASSIGNED)), Map.of());
        Decision d = AccessPolicy.decide(CATALOG, motoristaA, "trips", "view", NOW);
        assertThat(d.allowed()).isTrue();
        RecordRef propria = new RecordRef(ORG, BRANCH, Sector.ENTREGAS, UUID.randomUUID(), motoristaA.userId());
        RecordRef deOutro = new RecordRef(ORG, BRANCH, Sector.ENTREGAS, UUID.randomUUID(), UUID.randomUUID());
        RecordRef semAtribuicao = new RecordRef(ORG, BRANCH, Sector.ENTREGAS, motoristaA.userId(), null);
        assertThat(AccessPolicy.recordInScope(motoristaA, d.scopes(), propria)).isTrue();
        assertThat(AccessPolicy.recordInScope(motoristaA, d.scopes(), deOutro)).isFalse();
        assertThat(AccessPolicy.recordInScope(motoristaA, d.scopes(), semAtribuicao)).isFalse();
    }

    @Test
    void escopoDeUnidadeNaoAlcancaOutraUnidade() {
        Actor a = actor(false, Sector.RETIRADA, List.of(allow("orders.pickup", "view", Scope.BRANCH)), Map.of());
        Set<Scope> s = AccessPolicy.decide(CATALOG, a, "orders.pickup", "view", NOW).scopes();
        assertThat(AccessPolicy.recordInScope(a, s, new RecordRef(ORG, BRANCH, Sector.RETIRADA, null, null)))
                .isTrue();
        assertThat(AccessPolicy.recordInScope(a, s, new RecordRef(ORG, OTHER_BRANCH, Sector.RETIRADA, null, null)))
                .isFalse();
    }

    @Test
    void escopoDeSetorExigeMesmoSetorEMesmaUnidade() {
        Actor a = actor(false, Sector.ENTREGAS, List.of(allow("trips", "view", Scope.SECTOR)), Map.of());
        Set<Scope> s = AccessPolicy.decide(CATALOG, a, "trips", "view", NOW).scopes();
        assertThat(AccessPolicy.recordInScope(a, s, new RecordRef(ORG, BRANCH, Sector.ENTREGAS, null, null)))
                .isTrue();
        assertThat(AccessPolicy.recordInScope(a, s, new RecordRef(ORG, BRANCH, Sector.RETIRADA, null, null)))
                .isFalse();
        assertThat(AccessPolicy.recordInScope(a, s, new RecordRef(ORG, OTHER_BRANCH, Sector.ENTREGAS, null, null)))
                .isFalse();
    }

    @Test
    void outraOrganizacaoNuncaPassaNemComEscopoOrganizacao() {
        Actor admin = actor(true, Sector.ADMINISTRACAO, List.of(), Map.of());
        Set<Scope> s = AccessPolicy.decide(CATALOG, admin, "trips", "view", NOW).scopes();
        assertThat(AccessPolicy.recordInScope(admin, s, new RecordRef(OTHER_ORG, BRANCH, Sector.ENTREGAS, null,
                admin.userId()))).isFalse();
    }

    @Test
    void negacaoExplicitaVencePermissaoInclusiveDeAdministrador() {
        Grant deny = new Grant(UUID.randomUUID(), "dashboard", "view", Scope.ORGANIZATION, Effect.DENY, null, null);
        Actor a = actor(false, Sector.RETIRADA, List.of(allow("dashboard", "view", Scope.ORGANIZATION), deny),
                Map.of());
        Actor admin = actor(true, Sector.ADMINISTRACAO, List.of(deny), Map.of());
        assertThat(AccessPolicy.decide(CATALOG, a, "dashboard", "view", NOW).allowed()).isFalse();
        assertThat(AccessPolicy.decide(CATALOG, admin, "dashboard", "view", NOW).allowed()).isFalse();
    }

    @Test
    void concessaoExpiradaNaoValeEDentroDaValidadeVale() {
        Grant expirada = new Grant(UUID.randomUUID(), "trips", "view", Scope.ORGANIZATION, Effect.ALLOW, null,
                NOW.minusSeconds(1));
        Grant vigente = new Grant(UUID.randomUUID(), "trips", "confirm", Scope.ORGANIZATION, Effect.ALLOW, null,
                NOW.plusSeconds(60));
        Actor a = actor(false, Sector.ENTREGAS, List.of(expirada, vigente), Map.of());
        assertThat(AccessPolicy.decide(CATALOG, a, "trips", "view", NOW).allowed()).isFalse();
        assertThat(AccessPolicy.decide(CATALOG, a, "trips", "confirm", NOW).allowed()).isTrue();
    }

    @Test
    void alcadaUsaOMaiorLimiteConcedidoEmDecimalExato() {
        Grant g1 = new Grant(UUID.randomUUID(), "expenses", "approve", Scope.BRANCH, Effect.ALLOW,
                new BigDecimal("100.00"), null);
        Grant g2 = new Grant(UUID.randomUUID(), "expenses", "approve", Scope.BRANCH, Effect.ALLOW,
                new BigDecimal("250.50"), null);
        Actor a = actor(false, Sector.RETIRADA, List.of(g1, g2), Map.of());
        assertThat(AccessPolicy.decide(CATALOG, a, "expenses", "approve", NOW).maxAmount())
                .contains(new BigDecimal("250.50"));
        Actor semLimite = actor(false, Sector.RETIRADA,
                List.of(g1, allow("expenses", "approve", Scope.BRANCH)), Map.of());
        assertThat(AccessPolicy.decide(CATALOG, semLimite, "expenses", "approve", NOW).maxAmount()).isEmpty();
    }

    @Test
    void camposSensiveisNegadosSemConcessao() {
        Actor a = actor(false, Sector.CONTABILIDADE, List.of(), Map.of(FieldClass.PURCHASE_COST, Effect.ALLOW));
        assertThat(AccessPolicy.canSeeField(a, FieldClass.EMPLOYEE_SALARY)).isFalse();
        assertThat(AccessPolicy.canSeeField(a, FieldClass.PERSONAL_DOCUMENT)).isFalse();
        assertThat(AccessPolicy.canSeeField(a, FieldClass.PURCHASE_COST)).isTrue();
        Actor admin = actor(true, Sector.ADMINISTRACAO, List.of(), Map.of(FieldClass.BANK_DATA, Effect.DENY));
        assertThat(AccessPolicy.canSeeField(admin, FieldClass.EMPLOYEE_SALARY)).isTrue();
        assertThat(AccessPolicy.canSeeField(admin, FieldClass.BANK_DATA)).isFalse();
    }
}
