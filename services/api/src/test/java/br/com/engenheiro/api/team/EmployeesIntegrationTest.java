package br.com.engenheiro.api.team;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.TestIdentityProvisioner;
import br.com.engenheiro.api.support.TestJwt;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Pedido do responsável (ADR-027): cadastro de funcionários e cargos com campos protegidos, conta de login opcional
 * criada no provedor de identidade (NÃO CONFIGURADO sem credencial) e desligamento que bloqueia o acesso na hora.
 */
@IntegrationTest
class EmployeesIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person gerente;   // vê e mantém o cadastro da PRÓPRIA unidade, sem dados pessoais nem salário
    Person balcao;

    @BeforeEach
    void setUp() {
        TestIdentityProvisioner.reset();
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        gerente = fx.person(org, org.branch2(), RoleTemplate.BALCAO, "Gerente da Unidade 2");
        fx.grant(gerente, "users", "view", "BRANCH", null);
        fx.grant(gerente, "users", "edit_draft", "BRANCH", null);
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Paula do Balcão");
    }

    @AfterEach
    void tearDown() {
        TestIdentityProvisioner.reset();
    }

    String position(String name, String sector, String template) {
        Map<String, Object> b = new HashMap<>(Map.of("name", name, "sector", sector));
        b.put("suggested_template", template);
        Api.Res r = api.post("/api/v1/job-positions", patrao.token(), b, (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("id").asString();
    }

    JsonNode employee(Person who, String name, String positionId, UUID branch, Map<String, Object> extra) {
        Map<String, Object> b = new HashMap<>(Map.of("full_name", name, "job_position_id", positionId,
                "admission_date", "2026-03-02", "branch_id", branch.toString()));
        b.putAll(extra);
        Api.Res r = api.post("/api/v1/employees", who.token(), b, (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    // ---------- Cargos ----------
    @Test
    void cargosComNomeUnicoEArquivamentoProtegido() {
        String id = position("Balconista", "RETIRADA", "BALCAO");
        assertThat(api.post("/api/v1/job-positions", patrao.token(), Map.of("name", " balconista ", "sector", "RETIRADA"),
                (String) null).status()).isEqualTo(422); // mesmo nome (sem diferenciar maiúsculas)
        assertThat(api.post("/api/v1/job-positions", patrao.token(), Map.of("name", "Sócio", "sector", "ADMINISTRACAO",
                "suggested_template", "ADMIN"), (String) null).status()).isEqualTo(422); // administrador não é sugerível
        assertThat(api.post("/api/v1/job-positions", balcao.token(), Map.of("name", "Outro", "sector", "RETIRADA"),
                (String) null).status()).isEqualTo(403);
        employee(patrao, "Ana Fictícia", id, org.branch1(), Map.of());
        JsonNode list = api.get("/api/v1/job-positions", patrao.token()).json().path("items");
        assertThat(list.get(0).path("employees").asLong()).isEqualTo(1);
        // Com funcionário ativo no cargo, não arquiva.
        assertThat(api.put("/api/v1/job-positions/" + id, patrao.token(), Map.of("name", "Balconista", "sector", "RETIRADA",
                "status", "ARCHIVED"), "\"1\"").status()).isEqualTo(409);
        Api.Res ren = api.put("/api/v1/job-positions/" + id, patrao.token(), Map.of("name", "Atendente de balcão", "sector",
                "RETIRADA", "suggested_template", "BALCAO"), "\"1\"");
        assertThat(ren.status()).as(ren.body()).isEqualTo(200);
        assertThat(api.put("/api/v1/job-positions/" + id, patrao.token(), Map.of("name", "X", "sector", "RETIRADA"), "\"1\"")
                .status()).isEqualTo(412);
    }

    // ---------- Campos protegidos e escopo de unidade ----------
    @Test
    void cadastroComCamposProtegidosEEscopoDeUnidade() {
        String pos = position("Motorista", "ENTREGAS", "MOTORISTA");
        JsonNode e = employee(patrao, "Carlos Fictício", pos, org.branch2(), Map.of("cpf", "123.456.789-09", "birth_date",
                "1990-05-10", "address", "Rua Fictícia, 10", "phone", "(11) 90000-0000", "base_salary", "2500.00",
                "duties", "Entregas na zona norte", "work_schedule", "Seg a sex, 7h às 16h"));
        String id = e.path("id").asString();
        assertThat(e.path("cpf").asString()).isEqualTo("12345678909");
        assertThat(e.path("base_salary").asString()).isEqualTo("2500.00");
        employee(patrao, "Pessoa da Matriz", pos, org.branch1(), Map.of());

        // Gerente da unidade 2: vê só a própria unidade, sem CPF/nascimento/endereço/salário; telefone conforme o modelo.
        JsonNode list = api.get("/api/v1/employees", gerente.token()).json().path("items");
        assertThat(list).hasSize(1);
        JsonNode g = list.get(0);
        assertThat(g.path("full_name").asString()).isEqualTo("Carlos Fictício");
        assertThat(g.path("cpf").isNull()).isTrue();
        assertThat(g.path("address").isNull()).isTrue();
        assertThat(g.path("base_salary").isNull()).isTrue();
        assertThat(g.path("masked_fields").toString()).contains("cpf", "birth_date", "address", "base_salary");
        assertThat(api.get("/api/v1/employees", gerente.token()).body()).doesNotContain("12345678909").doesNotContain("2500");
        String matriz = api.get("/api/v1/employees?q=matriz", patrao.token()).json().path("items").get(0).path("id").asString();
        assertThat(api.get("/api/v1/employees/" + matriz, gerente.token()).status()).isEqualTo(404);

        // Gerente edita: não pode informar salário (oculto) e, ao salvar, salário/CPF ficam como estavam.
        Map<String, Object> upd = new HashMap<>(Map.of("full_name", "Carlos Fictício Souza", "job_position_id", pos,
                "admission_date", "2026-03-02", "base_salary", "9999.00"));
        Api.Res hidden = api.put("/api/v1/employees/" + id, gerente.token(), upd, "\"1\"");
        assertThat(hidden.status()).isEqualTo(422);
        assertThat(hidden.body()).contains("base_salary");
        upd.remove("base_salary");
        upd.put("status", "ON_LEAVE");
        Api.Res ok = api.put("/api/v1/employees/" + id, gerente.token(), upd, "\"1\"");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        JsonNode full = api.get("/api/v1/employees/" + id, patrao.token()).json();
        assertThat(full.path("full_name").asString()).isEqualTo("Carlos Fictício Souza");
        assertThat(full.path("status").asString()).isEqualTo("ON_LEAVE");
        assertThat(full.path("cpf").asString()).isEqualTo("12345678909");
        assertThat(full.path("base_salary").asString()).isEqualTo("2500.00");
        assertThat(full.path("address").asString()).isEqualTo("Rua Fictícia, 10");
        // CPF repetido é recusado; trilha só com NOMES de campo, nunca CPF ou salário.
        assertThat(api.post("/api/v1/employees", patrao.token(), Map.of("full_name", "Outro", "job_position_id", pos,
                "admission_date", "2026-03-02", "cpf", "12345678909"), (String) null).status()).isEqualTo(422);
        String trail = String.join("\n", jdbc.sql("SELECT coalesce(CAST(changes AS text), '') FROM audit_events WHERE entity_id = :e")
                .param("e", id).query(String.class).list());
        assertThat(trail).contains("base_salary").doesNotContain("12345678909").doesNotContain("2500").doesNotContain("Rua Fictícia");
        // Balcão (sem users.view) não lista funcionários.
        assertThat(api.get("/api/v1/employees", balcao.token()).status()).isEqualTo(403);
    }

    // ---------- Dar acesso aos apps: NÃO CONFIGURADO sem provedor; com provedor, conta real e sem escalada ----------
    @Test
    void darAcessoCriaContaNoProvedorENaoEscalaPermissao() {
        String pos = position("Balconista", "RETIRADA", "BALCAO");
        String id = employee(patrao, "Beatriz Fictícia", pos, org.branch1(), Map.of()).path("id").asString();
        Map<String, Object> body = Map.of("username", "beatriz.ficticia", "role_template", "BALCAO");

        Api.Res off = api.post("/api/v1/employees/" + id + "/access", patrao.token(), body, (String) null);
        assertThat(off.status()).isEqualTo(503);
        assertThat(off.body()).contains("NÃO CONFIGURADA");
        assertThat(api.get("/api/v1/employees/unlinked-accounts", patrao.token()).json().path("account_creation").asString())
                .isEqualTo("NOT_CONFIGURED");
        assertThat(api.get("/api/v1/employees/" + id, patrao.token()).json().path("account").isNull()).isTrue();

        TestIdentityProvisioner.enabled = true;
        assertThat(api.post("/api/v1/employees/" + id + "/access", patrao.token(), Map.of("username", "beatriz",
                "role_template", "ADMIN"), (String) null).status()).isEqualTo(422);
        // Quem não tem as capacidades do modelo não as concede (gerente sem pedidos/clientes de balcão... nem permissions.edit_draft).
        assertThat(api.post("/api/v1/employees/" + id + "/access", gerente.token(), body, (String) null).status()).isIn(403, 404);
        // RH da MESMA unidade, com cadastro e mapa de acessos, mas sem as capacidades de balcão: não concede balcão.
        Person rh = fx.person(org, org.branch1(), RoleTemplate.RECEBIMENTO, "RH Fictício");
        fx.grant(rh, "users", "view", "BRANCH", null);
        fx.grant(rh, "users", "edit_draft", "BRANCH", null);
        fx.grant(rh, "permissions", "edit_draft", "BRANCH", null);
        Api.Res esc = api.post("/api/v1/employees/" + id + "/access", rh.token(), body, (String) null);
        assertThat(esc.status()).as(esc.body()).isEqualTo(403);
        assertThat(esc.body()).contains("orders.pickup");
        TestIdentityProvisioner.down = true;
        assertThat(api.post("/api/v1/employees/" + id + "/access", patrao.token(), body, (String) null).status()).isEqualTo(503);
        TestIdentityProvisioner.down = false;

        Api.Res r = api.post("/api/v1/employees/" + id + "/access", patrao.token(), body, (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("temporary_password").asString()).hasSizeGreaterThan(8);
        assertThat(r.header("Cache-Control")).contains("no-store");
        JsonNode emp = r.json().path("employee");
        assertThat(emp.path("account").path("role_template").asString()).isEqualTo("BALCAO");
        assertThat(emp.path("account").path("status").asString()).isEqualTo("ACTIVE");
        // A pessoa entra no app Retirada com a PRÓPRIA conta e só com o modelo de balcão.
        String subject = TestIdentityProvisioner.ACCOUNTS.get("beatriz.ficticia")[0];
        Api.Res me = api.get("/api/v1/me/capabilities", TestJwt.token(subject, AppClients.RETIRADA));
        assertThat(me.status()).as(me.body()).isEqualTo(200);
        assertThat(me.json().path("user").path("display_name").asString()).isEqualTo("Beatriz Fictícia");
        assertThat(me.body()).contains("orders.pickup.view").doesNotContain("reports.view");
        assertThat(api.get("/api/v1/me/capabilities", TestJwt.token(subject, AppClients.WEB)).status()).isEqualTo(403);
        // Senha nunca vai para a trilha; segunda vez: já tem conta.
        String trail = String.join("\n", jdbc.sql("SELECT coalesce(CAST(changes AS text), '') FROM audit_events WHERE entity_id = :e")
                .param("e", id).query(String.class).list());
        assertThat(trail).contains("beatriz.ficticia").doesNotContain(r.json().path("temporary_password").asString());
        assertThat(api.post("/api/v1/employees/" + id + "/access", patrao.token(), body, (String) null).status()).isEqualTo(409);
        // Nome de usuário de outra pessoa: recusado.
        String other = employee(patrao, "Outra Fictícia", pos, org.branch1(), Map.of()).path("id").asString();
        assertThat(api.post("/api/v1/employees/" + other + "/access", patrao.token(), body, (String) null).status()).isEqualTo(422);

        // Desligamento bloqueia a conta na hora; desligado não volta a ativo por edição.
        Api.Res t = api.post("/api/v1/employees/" + id + "/termination", patrao.token(), Map.of("termination_date", "2026-11-17",
                "reason", "Pedido de demissão (fictício)"), "\"" + emp.path("version").asLong() + "\"");
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        assertThat(t.json().path("status").asString()).isEqualTo("TERMINATED");
        assertThat(t.json().path("account").path("status").asString()).isEqualTo("DISABLED");
        assertThat(api.get("/api/v1/me/capabilities", TestJwt.token(subject, AppClients.RETIRADA)).status()).isIn(401, 403);
        assertThat(api.put("/api/v1/employees/" + id, patrao.token(), Map.of("full_name", "Beatriz Fictícia", "job_position_id", pos,
                "admission_date", "2026-03-02", "status", "ACTIVE"), "\"" + t.json().path("version").asLong() + "\"").status())
                .isEqualTo(422);
    }

    // ---------- Ligar conta que já existe ----------
    @Test
    void ligarContaExistenteSemDuplicar() {
        String pos = position("Balconista", "RETIRADA", "BALCAO");
        String id = employee(patrao, "Paula do Balcão", pos, org.branch1(), Map.of()).path("id").asString();
        List<String> names = api.get("/api/v1/employees/unlinked-accounts", patrao.token()).json().path("items")
                .valueStream().map(a -> a.path("display_name").asString()).toList();
        assertThat(names).contains("Paula do Balcão");
        Api.Res l = api.post("/api/v1/employees/" + id + "/account-link", patrao.token(), Map.of("user_id",
                balcao.userId().toString()), (String) null);
        assertThat(l.status()).as(l.body()).isEqualTo(200);
        assertThat(l.json().path("account").path("display_name").asString()).isEqualTo("Paula do Balcão");
        String other = employee(patrao, "Homônima", pos, org.branch1(), Map.of()).path("id").asString();
        assertThat(api.post("/api/v1/employees/" + other + "/account-link", patrao.token(), Map.of("user_id",
                balcao.userId().toString()), (String) null).status()).isEqualTo(422);
    }
}
