package br.com.engenheiro.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.application.OwnerBootstrap;
import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.TestJwt;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * F16 (D06): banco VAZIO (contexto próprio = contêiner próprio). O bootstrap cria empresa, matriz e o dono
 * administrador; o dono entra pelo site e pelo app Patrão; rodar de novo não cria nada; o dono cria o resto pelo
 * sistema. Ninguém mais existe — sem seed.
 */
@IntegrationTest
@TestPropertySource(properties = {"engenheiro.bootstrap.owner-subject=dono-real-no-idp",
        "engenheiro.bootstrap.owner-name=Dono da Distribuidora"})
class OwnerBootstrapIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    OwnerBootstrap bootstrap;

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void bancoVazioGanhaEmpresaMatrizEDonoUmaVezSo() throws Exception {
        assertThat(count("organizations")).isEqualTo(1);
        assertThat(count("branches")).isEqualTo(1);
        // Além do usuário técnico das integrações (migration), só a conta do dono vinda do IdP.
        assertThat(jdbc.sql("SELECT count(*) FROM app_users WHERE issuer = :i").param("i", TestJwt.ISSUER)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(count("memberships")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT name FROM organizations").query(String.class).single())
                .isEqualTo("Distribuidora de Cana Engenheiro Ltda");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'identity.bootstrap_owner'")
                .query(Long.class).single()).isEqualTo(1);

        Api api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        for (String client : new String[] {AppClients.WEB, AppClients.GESTAO}) {
            Api.Res me = api.get("/api/v1/me/capabilities", TestJwt.token("dono-real-no-idp", client));
            assertThat(me.status()).as(me.body()).isEqualTo(200);
            assertThat(me.json().path("admin").asBoolean()).isTrue();
            assertThat(me.json().path("user").path("display_name").asString()).isEqualTo("Dono da Distribuidora");
        }
        // Outra conta do IdP não ganha nada; o app de outro setor não serve ao dono.
        assertThat(api.get("/api/v1/me/capabilities", TestJwt.token("intruso", AppClients.WEB)).status()).isIn(401, 403);
        assertThat(api.get("/api/v1/me/capabilities", TestJwt.token("dono-real-no-idp", AppClients.RETIRADA)).status())
                .isIn(401, 403);

        bootstrap.run(new DefaultApplicationArguments());
        assertThat(count("organizations")).isEqualTo(1);
        assertThat(count("memberships")).isEqualTo(1);

        // O dono segue pelo sistema: cadastra a segunda unidade com a identidade dele.
        JsonNode b = api.post("/api/v1/branches", TestJwt.token("dono-real-no-idp", AppClients.WEB),
                Map.of("name", "Unidade 2"), (String) null).json();
        assertThat(b.path("name").asString()).isEqualTo("Unidade 2");
    }
}
