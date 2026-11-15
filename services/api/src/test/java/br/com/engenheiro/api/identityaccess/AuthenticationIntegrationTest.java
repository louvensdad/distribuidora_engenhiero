package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.TestJwt;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cenário S01: token ausente/expirado/assinatura/emissor/audiência inválidos e vínculo inativo são negados. */
@IntegrationTest
class AuthenticationIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person balcao;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão Teste");
    }

    @Test
    void semTokenRetorna401ComErroPadronizado() {
        Api.Res r = api.get("/api/v1/me/capabilities", null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.json().path("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.json().path("request_id").asString()).isNotBlank();
        assertThat(r.header("X-Request-Id")).isEqualTo(r.json().path("request_id").asString());
    }

    @Test
    void tokenValidoComVinculoAtivoAutentica() {
        Api.Res r = api.get("/api/v1/me/capabilities", balcao.token());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("organization").path("name").asString())
                .isEqualTo("Distribuidora de Cana Engenheiro Ltda");
        assertThat(r.json().path("client_id").asString()).isEqualTo(AppClients.RETIRADA);
    }

    @Test
    void tokenExpiradoRetorna401() {
        String t = TestJwt.builder(balcao.subject(), AppClients.RETIRADA)
                .expiresAt(Instant.now().minusSeconds(3600)).sign();
        assertThat(api.get("/api/v1/me/capabilities", t).status()).isEqualTo(401);
    }

    @Test
    void assinaturaDeOutraChaveRetorna401() {
        String t = TestJwt.builder(balcao.subject(), AppClients.RETIRADA).signedWith(TestJwt.OTHER_KEYS).sign();
        assertThat(api.get("/api/v1/me/capabilities", t).status()).isEqualTo(401);
    }

    @Test
    void emissorIncorretoRetorna401() {
        String t = TestJwt.builder(balcao.subject(), AppClients.RETIRADA)
                .issuer("https://outro-idp.invalid/realms/x").sign();
        assertThat(api.get("/api/v1/me/capabilities", t).status()).isEqualTo(401);
    }

    @Test
    void audienciaIncorretaRetorna401() {
        String t = TestJwt.builder(balcao.subject(), AppClients.RETIRADA).audience(List.of("account")).sign();
        assertThat(api.get("/api/v1/me/capabilities", t).status()).isEqualTo(401);
    }

    @Test
    void tokenMalformadoRetorna401() {
        assertThat(api.get("/api/v1/me/capabilities", "abc.def.ghi").status()).isEqualTo(401);
    }

    @Test
    void subjectSemPessoaCadastradaRetorna403SemDados() {
        Api.Res r = api.get("/api/v1/me/capabilities",
                TestJwt.token(UUID.randomUUID().toString(), AppClients.RETIRADA));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.json().path("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(r.body()).doesNotContain("capabilities", "organization");
    }

    @Test
    void contaBloqueadaRetorna403() {
        jdbc.sql("UPDATE app_users SET status = 'DISABLED' WHERE id = :u").param("u", balcao.userId()).update();
        assertThat(api.get("/api/v1/me/capabilities", balcao.token()).status()).isEqualTo(403);
    }

    @Test
    void vinculoInativoRetorna403() {
        jdbc.sql("UPDATE memberships SET status = 'DISABLED' WHERE id = :m")
                .param("m", balcao.membershipId()).update();
        assertThat(api.get("/api/v1/me/capabilities", balcao.token()).status()).isEqualTo(403);
    }

    @Test
    void aplicativoNaoLiberadoParaOVinculoRetorna403() {
        // Balconista tentando entrar pelo App Patrão: o client não concede acesso.
        Api.Res r = api.get("/api/v1/me/capabilities", balcao.tokenFor(AppClients.GESTAO));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void clientDesconhecidoRetorna403() {
        assertThat(api.get("/api/v1/me/capabilities", balcao.tokenFor("app-pirata")).status()).isEqualTo(403);
    }

    @Test
    void saudeEProntidaoSaoPublicasSemDetalhes() {
        Api.Res r = api.get("/actuator/health/readiness", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("status").asString()).isEqualTo("UP");
        assertThat(r.body()).doesNotContain("postgres", "jdbc");
    }

    @Test
    void rotaForaDaApiENegada() {
        assertThat(api.get("/qualquer-coisa", balcao.token()).status()).isIn(401, 403);
    }
}
