package br.com.engenheiro.api.catalogparties;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Escopo por registro e mascaramento de campos numa entidade real (gate F01 + fatia F03). */
@IntegrationTest
class CustomersIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person balcaoMatriz;
    Person balcaoUnidade2;
    Person coordenador;
    Person contador;
    Person motorista;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        balcaoMatriz = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão Matriz");
        balcaoUnidade2 = fx.person(org, org.branch2(), RoleTemplate.BALCAO, "Balcão Unidade 2");
        coordenador = fx.person(org, org.branch1(), RoleTemplate.COORDENADOR_ENTREGAS, "Coordenação");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Contador");
        motorista = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Motorista");
    }

    private String createAsPatrao(String name) {
        Api.Res r = api.post("/api/v1/customers", patrao.token(), Map.of("name", name, "contact", "(11) 90000-0000",
                "address", "Rua Exemplo, 100", "personal_document", "000.000.000-00".replace("0", "1")), null);
        assertThat(r.status()).isEqualTo(201);
        return r.json().path("id").asString();
    }

    @Test
    void outraUnidadeNaoConsultaClientePorIdConhecido() {
        Api.Res created = api.post("/api/v1/customers", balcaoMatriz.token(),
                Map.of("name", "Cliente Matriz", "contact", "(11) 95555-0000"), null);
        assertThat(created.status()).isEqualTo(201);
        String id = created.json().path("id").asString();

        assertThat(api.get("/api/v1/customers/" + id, balcaoMatriz.token()).status()).isEqualTo(200);
        Api.Res other = api.get("/api/v1/customers/" + id, balcaoUnidade2.token());
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.body()).doesNotContain("Cliente Matriz", "95555");
        // Mesmo 404 de um ID inexistente: não revela existência.
        assertThat(api.get("/api/v1/customers/" + UUID.randomUUID(), balcaoUnidade2.token()).status())
                .isEqualTo(404);
        assertThat(names(api.get("/api/v1/customers", balcaoUnidade2.token()))).doesNotContain("Cliente Matriz");
        assertThat(names(api.get("/api/v1/customers", balcaoMatriz.token()))).contains("Cliente Matriz");

        int denied = jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'access.denied' AND entity_id = :id "
                + "AND reason = 'registro fora do escopo'").param("id", id).query(Integer.class).single();
        assertThat(denied).isEqualTo(1);
    }

    @Test
    void patraoComEscopoDeOrganizacaoVeTodasAsUnidades() {
        Api.Res created = api.post("/api/v1/customers", balcaoUnidade2.token(), Map.of("name", "Cliente U2"), null);
        assertThat(api.get("/api/v1/customers/" + created.json().path("id").asString(), patrao.token()).status())
                .isEqualTo(200);
        assertThat(names(api.get("/api/v1/customers", patrao.token()))).contains("Cliente U2");
    }

    @Test
    void outraOrganizacaoNaoVeNemComoAdministrador() {
        String id = createAsPatrao("Cliente Org A");
        Org outra = fx.org();
        Person adminB = fx.person(outra, outra.branch1(), RoleTemplate.ADMIN, "Admin B");
        assertThat(api.get("/api/v1/customers/" + id, adminB.token()).status()).isEqualTo(404);
        assertThat(names(api.get("/api/v1/customers", adminB.token()))).isEmpty();
    }

    @Test
    void camposSensiveisSaoMascaradosConformePolitica() {
        String id = createAsPatrao("Cliente Completo");
        JsonNode full = api.get("/api/v1/customers/" + id, patrao.token()).json();
        assertThat(full.path("personal_document").asString()).isEqualTo("11111111111");
        assertThat(full.path("masked_fields").size()).isZero();

        JsonNode balcao = api.get("/api/v1/customers/" + id, balcaoMatriz.token()).json();
        assertThat(balcao.path("contact").asString()).isEqualTo("(11) 90000-0000");
        assertThat(balcao.path("address").isNull()).isTrue();
        assertThat(balcao.path("personal_document").isNull()).isTrue();
        assertThat(list(balcao.path("masked_fields"))).containsExactlyInAnyOrder("address", "personal_document");

        JsonNode coord = api.get("/api/v1/customers/" + id, coordenador.token()).json();
        assertThat(coord.path("address").asString()).isEqualTo("Rua Exemplo, 100");
        assertThat(coord.path("personal_document").isNull()).isTrue();

        String listBody = api.get("/api/v1/customers", balcaoMatriz.token()).body();
        assertThat(listBody).doesNotContain("11111111111", "Rua Exemplo");
    }

    @Test
    void quemNaoVeOCampoTambemNaoOGrava() {
        Api.Res r = api.post("/api/v1/customers", balcaoMatriz.token(),
                Map.of("name", "Cliente X", "personal_document", "11122233344"), null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).contains("personal_document").doesNotContain("11122233344");
    }

    @Test
    void semCapacidadeRetorna403MesmoComIdValido() {
        String id = createAsPatrao("Cliente Y");
        assertThat(api.get("/api/v1/customers/" + id, contador.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/customers", motorista.token()).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/customers", contador.token(), Map.of("name", "Z"), null).status())
                .isEqualTo(403);
    }

    @Test
    void organizacaoUnidadeEAutorDoCorpoSaoIgnorados() {
        Org outra = fx.org();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Cliente Forjado");
        body.put("organization_id", outra.id().toString());
        body.put("branch_id", org.branch2().toString());
        body.put("created_by", patrao.userId().toString());
        Api.Res r = api.post("/api/v1/customers", balcaoMatriz.token(), body, null);
        assertThat(r.status()).isEqualTo(201);
        Map<String, Object> row = jdbc.sql("SELECT organization_id, branch_id, created_by FROM customers WHERE id = :id")
                .param("id", UUID.fromString(r.json().path("id").asString())).query().singleRow();
        assertThat(row.get("organization_id")).isEqualTo(org.id());
        assertThat(row.get("branch_id")).isEqualTo(org.branch1());
        assertThat(row.get("created_by")).isEqualTo(balcaoMatriz.userId());
    }

    @Test
    void auditoriaNaoGuardaDadoPessoal() {
        String id = createAsPatrao("Cliente Auditado");
        String changes = jdbc.sql("SELECT changes::text FROM audit_events WHERE entity_id = :id AND action = 'customers.create'")
                .param("id", id).query(String.class).single();
        assertThat(changes).contains("personal_document").doesNotContain("11111111111", "Rua Exemplo", "90000");
    }

    @Test
    void validacaoEDocumentoDuplicado() {
        assertThat(api.post("/api/v1/customers", patrao.token(), Map.of("name", " "), null).status()).isEqualTo(422);
        assertThat(api.post("/api/v1/customers", patrao.token(), Map.of("name", "A", "personal_document", "123"),
                null).status()).isEqualTo(422);
        createAsPatrao("Primeiro");
        Api.Res dup = api.post("/api/v1/customers", patrao.token(),
                Map.of("name", "Segundo", "personal_document", "11111111111"), null);
        assertThat(dup.status()).isEqualTo(422);
    }

    @Test
    void paginacaoPorCursorEFiltroPorNome() {
        for (int i = 0; i < 5; i++) {
            api.post("/api/v1/customers", patrao.token(), Map.of("name", "Pag " + i), null);
        }
        Api.Res p1 = api.get("/api/v1/customers?q=Pag&limit=2", patrao.token());
        assertThat(names(p1)).containsExactly("Pag 0", "Pag 1");
        Api.Res p2 = api.get("/api/v1/customers?q=Pag&limit=2&cursor=" + p1.json().path("next_cursor").asString(),
                patrao.token());
        assertThat(names(p2)).containsExactly("Pag 2", "Pag 3");
    }

    @Test
    void motoristaNaoRecebeCadastroDeClientePeloAppDeEntregas() {
        String id = createAsPatrao("Cliente W");
        assertThat(api.get("/api/v1/customers/" + id, motorista.tokenFor(AppClients.ENTREGAS)).status())
                .isEqualTo(403);
    }

    private static List<String> names(Api.Res r) {
        List<String> out = new ArrayList<>();
        r.json().path("items").forEach(i -> out.add(i.path("name").asString()));
        return out;
    }

    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(i -> out.add(i.asString()));
        return out;
    }
}
