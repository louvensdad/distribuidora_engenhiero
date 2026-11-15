package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AuthorizationIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person balcao;
    Person contador;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Teste");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão Teste");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Contador Teste");
    }

    private static List<String> caps(Api.Res r) {
        List<String> out = new ArrayList<>();
        r.json().path("capabilities").forEach(c -> out.add(c.path("capability").asString()));
        return out;
    }

    @Test
    void balcaoRecebeSomenteCapacidadesOperacionaisECamposPermitidos() {
        Api.Res r = api.get("/api/v1/me/capabilities", balcao.token());
        assertThat(caps(r)).contains("orders.pickup.create", "orders.pickup.view")
                .doesNotContain("dashboard.view", "reports.view", "payroll.view", "audit.view",
                        "permissions.edit_draft", "orders.pickup.confirm", "payments.confirm");
        JsonNode fields = r.json().path("fields");
        assertThat(fields.path("purchase_cost").asBoolean()).isFalse();
        assertThat(fields.path("employee_salary").asBoolean()).isFalse();
        assertThat(fields.path("operational_contact").asBoolean()).isTrue();
    }

    @Test
    void contadorNaoRecebeDashboardNemAprovacao() {
        Api.Res r = api.get("/api/v1/me/capabilities", contador.token());
        assertThat(caps(r)).contains("taxes.create").doesNotContain("dashboard.view", "taxes.approve",
                "reports.view", "company_cash.view");
        assertThat(r.json().path("fields").path("employee_salary").asBoolean()).isFalse();
    }

    @Test
    void patraoTemTodasAsCapacidadesDoCatalogo() {
        Api.Res r = api.get("/api/v1/me/capabilities", patrao.token());
        assertThat(caps(r)).contains("dashboard.view", "audit.view", "permissions.edit_draft", "taxes.approve");
        assertThat(r.json().path("admin").asBoolean()).isTrue();
    }

    /** S02: balconista tenta histórico, usuários e permissões por URL. */
    @Test
    void balcaoNaoAcessaHistoricoUsuariosNemPermissoes() {
        assertThat(api.get("/api/v1/audit-events", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/users", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/users/" + contador.userId() + "/access-policy", balcao.token()).status())
                .isEqualTo(403);
    }

    @Test
    void naoPromoveAPropriaContaNemComCapacidadeDeGestao() {
        // Mesmo com permissions.edit_draft concedido, ninguém altera o próprio acesso.
        Map<String, Object> body = policy(List.of(grant("permissions", "edit_draft"), grant("permissions", "view"),
                grant("users", "view")), Set.of(AppClients.RETIRADA), "teste de gestão delegada");
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        assertThat(api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(), body, etag)
                .status()).isEqualTo(200);

        Map<String, Object> self = new LinkedHashMap<>();
        self.put("template", "ADMIN");
        self.put("reason", "quero ser dono");
        Api.Res r = api.post("/api/v1/users/" + balcao.userId() + "/access-policy", balcao.token(), self, "\"2\"");
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void gestorDelegadoNaoConcedeOQueNaoPossui() {
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(grant("permissions", "edit_draft"), grant("permissions", "view")),
                        Set.of(AppClients.RETIRADA), "delegar"), etag);

        String etagC = api.get("/api/v1/users/" + contador.userId() + "/access-policy", patrao.token())
                .header("ETag");
        Api.Res r = api.post("/api/v1/users/" + contador.userId() + "/access-policy", balcao.token(),
                policy(List.of(grant("dashboard", "view")), Set.of(AppClients.CONTABIL), "escalada"), etagC);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).contains("não possui");
    }

    @Test
    void gestorDelegadoNaoAlteraAdministrador() {
        Person outroAdmin = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Sócio Teste");
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(grant("permissions", "edit_draft"), grant("permissions", "view")),
                        Set.of(AppClients.RETIRADA), "delegar"), etag);
        Map<String, Object> demote = new LinkedHashMap<>();
        demote.put("template", "BALCAO");
        demote.put("reason", "rebaixar");
        Api.Res r = api.post("/api/v1/users/" + outroAdmin.userId() + "/access-policy", balcao.token(), demote,
                "\"1\"");
        assertThat(r.status()).isEqualTo(403);
    }

    /** SEC-05 / S05: revogar concessão vale na próxima chamada protegida. */
    @Test
    void revogacaoImpedeAProximaChamada() {
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        Api.Res granted = api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(grant("audit", "view")), Set.of(AppClients.RETIRADA), "consulta temporária"), etag);
        assertThat(granted.status()).isEqualTo(200);
        assertThat(api.get("/api/v1/audit-events", balcao.token()).status()).isEqualTo(200);

        Api.Res revoked = api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(), Set.of(AppClients.RETIRADA), "fim da consulta"), granted.header("ETag"));
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(api.get("/api/v1/audit-events", balcao.token()).status()).isEqualTo(403);
    }

    @Test
    void bloqueioDeContaImpedeAProximaChamadaComOMesmoToken() {
        String token = balcao.token();
        assertThat(api.get("/api/v1/me/capabilities", token).status()).isEqualTo(200);
        Api.Res r = api.post("/api/v1/users/" + balcao.userId() + "/status", patrao.token(),
                Map.of("status", "DISABLED", "reason", "desligamento"), "\"1\"");
        assertThat(r.status()).isEqualTo(200);
        assertThat(api.get("/api/v1/me/capabilities", token).status()).isEqualTo(403);
    }

    @Test
    void removerAplicativoDoVinculoBloqueiaAqueleApp() {
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(grant("orders.pickup", "view")), Set.of(AppClients.ENTREGAS), "mudou de setor"),
                etag);
        assertThat(api.get("/api/v1/me/capabilities", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/me/capabilities", balcao.tokenFor(AppClients.ENTREGAS)).status())
                .isEqualTo(200);
    }

    @Test
    void naoBloqueiaOUltimoAdministradorNemASiMesmo() {
        Api.Res self = api.post("/api/v1/users/" + patrao.userId() + "/status", patrao.token(),
                Map.of("status", "DISABLED", "reason", "teste"), "\"1\"");
        assertThat(self.status()).isEqualTo(403);

        Person socio = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Sócio Teste");
        assertThat(api.post("/api/v1/users/" + socio.userId() + "/status", patrao.token(),
                Map.of("status", "DISABLED", "reason", "saída do sócio"), "\"1\"").status()).isEqualTo(200);
        // Agora o patrão é o último administrador ativo: rebaixá-lo é recusado mesmo por outro caminho.
        Map<String, Object> demote = new LinkedHashMap<>();
        demote.put("template", "BALCAO");
        demote.put("reason", "teste");
        assertThat(api.post("/api/v1/users/" + patrao.userId() + "/access-policy", patrao.token(), demote,
                "\"1\"").status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM memberships WHERE organization_id = :o AND is_admin "
                + "AND status = 'ACTIVE'").param("o", org.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void versaoDesatualizadaRetorna412() {
        Api.Res r = api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(), Set.of(AppClients.RETIRADA), "teste"), "\"999\"");
        assertThat(r.status()).isEqualTo(412);
        assertThat(r.json().path("code").asString()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void semIfMatchOuMotivoERecusado() {
        assertThat(api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(), Set.of(AppClients.RETIRADA), "x"), null).status()).isEqualTo(422);
        assertThat(api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(), Set.of(AppClients.RETIRADA), " "), "\"1\"").status()).isEqualTo(422);
    }

    @Test
    void capacidadeForaDoCatalogoERecusada() {
        Api.Res r = api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(grant("integration.secret", "read")), Set.of(AppClients.RETIRADA), "x"), "\"1\"");
        assertThat(r.status()).isEqualTo(422);
    }

    /** S06: IDs de outra organização não ampliam acesso, mesmo conhecidos. */
    @Test
    void administradorDeOutraOrganizacaoNaoVePessoaPorIdConhecido() {
        Org outra = fx.org();
        Person adminOutra = fx.person(outra, outra.branch1(), RoleTemplate.ADMIN, "Admin Outra");
        assertThat(api.get("/api/v1/users/" + balcao.userId() + "/access-policy", adminOutra.token()).status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/users/" + balcao.userId() + "/status", adminOutra.token(),
                Map.of("status", "DISABLED", "reason", "ataque"), "\"1\"").status()).isEqualTo(404);
        JsonNode users = api.get("/api/v1/users", adminOutra.token()).json().path("items");
        assertThat(users.toString()).doesNotContain(balcao.userId().toString());
    }

    @Test
    void campoExtraNoCorpoNaoMudaAtorNemOrganizacao() {
        Map<String, Object> body = policy(List.of(grant("orders.pickup", "view")), Set.of(AppClients.RETIRADA),
                "ajuste");
        body.put("organization_id", "00000000-0000-0000-0000-000000000000");
        body.put("actor_id", contador.userId().toString());
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        assertThat(api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(), body, etag)
                .status()).isEqualTo(200);
        String actor = jdbc.sql("SELECT actor_user_id::text FROM audit_events WHERE action = 'permissions.replace' "
                        + "AND organization_id = :o ORDER BY occurred_at DESC LIMIT 1")
                .param("o", org.id()).query(String.class).single();
        assertThat(actor).isEqualTo(patrao.userId().toString());
    }

    @Test
    void historicoRegistraAtorRealENegativas() {
        api.get("/api/v1/audit-events", balcao.token()); // negado e auditado
        String etag = api.get("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token()).header("ETag");
        api.post("/api/v1/users/" + balcao.userId() + "/access-policy", patrao.token(),
                policy(List.of(), Set.of(AppClients.RETIRADA), "revisão"), etag);

        JsonNode items = api.get("/api/v1/audit-events?limit=100", patrao.token()).json().path("items");
        List<String> rows = new ArrayList<>();
        items.forEach(i -> rows.add(i.path("action").asString() + "|" + i.path("result").asString() + "|"
                + i.path("actor_name").asString()));
        assertThat(rows).contains("access.denied|DENIED|Balcão Teste", "permissions.replace|SUCCESS|Patrão Teste");
        assertThat(items.toString()).doesNotContain("Bearer", "eyJ");
    }

    @Test
    void auditoriaEImutavelNoBanco() {
        api.get("/api/v1/audit-events", balcao.token());
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET reason = 'adulterado'").update())
                .hasMessageContaining("somente inclusão");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
                .hasMessageContaining("somente inclusão");
    }

    @Test
    void historicoPaginaPorCursorSemRepetir() {
        for (int i = 0; i < 5; i++) {
            api.get("/api/v1/audit-events", balcao.token());
        }
        Api.Res p1 = api.get("/api/v1/audit-events?limit=2", patrao.token());
        String cursor = p1.json().path("next_cursor").asString();
        assertThat(cursor).isNotBlank();
        Api.Res p2 = api.get("/api/v1/audit-events?limit=2&cursor=" + cursor, patrao.token());
        assertThat(p2.json().path("items").get(0).path("id").asString())
                .isNotEqualTo(p1.json().path("items").get(1).path("id").asString());
        assertThat(api.get("/api/v1/audit-events?limit=101", patrao.token()).status()).isEqualTo(422);
    }

    /** PROD-05: sem limite fixo de oito funcionários. */
    @Test
    void suportaMaisDeOitoFuncionarios() {
        for (int i = 0; i < 10; i++) {
            fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Funcionário " + i);
        }
        assertThat(api.get("/api/v1/users", patrao.token()).json().path("items").size()).isEqualTo(13);
    }

    private static Map<String, Object> grant(String resource, String action) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("resource", resource);
        g.put("action", action);
        g.put("scope", "ORGANIZATION");
        g.put("effect", "ALLOW");
        return g;
    }

    private static Map<String, Object> policy(List<Map<String, Object>> grants, Set<String> clients, String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("grants", grants);
        b.put("fields", List.of());
        b.put("clients", clients);
        b.put("reason", reason);
        return b;
    }
}
