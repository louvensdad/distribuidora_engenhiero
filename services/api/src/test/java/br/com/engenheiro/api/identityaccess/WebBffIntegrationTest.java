package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Browser;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.StubIdp;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SEC-01/SEC-06: site do patrão via BFF. Login Authorization Code + PKCE feito pelo servidor; o navegador só recebe
 * cookie de sessão HttpOnly e o token de CSRF. Mutações por cookie exigem CSRF.
 */
@IntegrationTest
class WebBffIntegrationTest {
    static final StubIdp IDP = new StubIdp();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void webLogin(DynamicPropertyRegistry r) {
        r.add("engenheiro.web.client-secret", () -> StubIdp.CLIENT_SECRET);
        r.add("engenheiro.web.authorization-uri", () -> IDP.base() + "/auth");
        r.add("engenheiro.web.token-uri", () -> IDP.base() + "/token");
        r.add("engenheiro.web.end-session-uri", () -> IDP.base() + "/logout");
        r.add("engenheiro.web.app-url", () -> "http://localhost:4200/");
        r.add("engenheiro.security.oidc.jwk-set-uri", () -> IDP.base() + "/jwks");
        r.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    Browser browser;
    Org org;
    Person patrao;

    @BeforeEach
    void setUp() {
        browser = new Browser(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Teste");
        IDP.pkceVerified = false;
    }

    @AfterEach
    void reset() {
        IDP.accessTokenTtlSeconds = 300;
    }

    /** Faz o papel do usuário no IdP e conclui o login. Retorna o redirecionamento final. */
    private HttpResponse<String> login(Person p) {
        HttpResponse<String> start = browser.get("/oauth2/authorization/engenheiro");
        assertThat(start.statusCode()).isEqualTo(302);
        URI auth = URI.create(start.headers().firstValue("Location").orElseThrow());
        Map<String, String> q = StubIdp.parse(auth.getRawQuery());
        String code = IDP.issueCode(p.subject(), q.get("nonce"), q.get("code_challenge"));
        return browser.get(q.get("redirect_uri") + "?code=" + code + "&state=" + q.get("state"));
    }

    private Map<String, String> csrf() {
        return Map.of("X-XSRF-TOKEN", browser.cookie("XSRF-TOKEN"), "If-Match", "\"1\"");
    }

    @Test
    void sessaoAnonimaInformaLoginConfiguradoEEmiteCsrf() {
        HttpResponse<String> r = browser.get("/bff/session");
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.path("web_login").asString()).isEqualTo("CONFIGURADO");
        assertThat(body.path("authenticated").asBoolean()).isFalse();
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank();
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(401);
    }

    @Test
    void pedidoDeAutorizacaoUsaPkceS256ECookieHttpOnlySameSite() {
        HttpResponse<String> start = browser.get("/oauth2/authorization/engenheiro");
        Map<String, String> q = StubIdp.parse(URI.create(start.headers().firstValue("Location").orElseThrow())
                .getRawQuery());
        assertThat(q).containsEntry("code_challenge_method", "S256").containsEntry("client_id", "engenheiro-web")
                .containsKeys("code_challenge", "state", "nonce");
        assertThat(q).doesNotContainKey("client_secret");
        String setCookie = start.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("ENGENHEIRO_SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void loginCompletoDaAcessoAApiPelaSessaoSemExporTokens() {
        browser.get("/oauth2/authorization/engenheiro");
        String sessionBefore = browser.cookie("ENGENHEIRO_SESSION");
        HttpResponse<String> done = login(patrao);
        assertThat(done.statusCode()).isEqualTo(302);
        assertThat(done.headers().firstValue("Location")).contains("http://localhost:4200/");
        assertThat(IDP.pkceVerified).isTrue();
        assertThat(browser.cookie("ENGENHEIRO_SESSION")).isNotEqualTo(sessionBefore); // contra fixação

        HttpResponse<String> me = browser.get("/api/v1/me/capabilities");
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(me.body());
        assertThat(body.path("client_id").asString()).isEqualTo("engenheiro-web");
        assertThat(body.path("admin").asBoolean()).isTrue();

        HttpResponse<String> session = browser.get("/bff/session");
        assertThat(JSON.readTree(session.body()).path("authenticated").asBoolean()).isTrue();
        assertThat(session.body() + me.body()).doesNotContain("eyJ", "refresh");
        assertThat(browser.allCookies()).noneMatch(c -> c.getValue().startsWith("eyJ"));
    }

    @Test
    void mutacaoPorCookieExigeCsrf() {
        Person balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão Teste");
        login(patrao);
        browser.get("/bff/session");
        String body = "{\"status\":\"DISABLED\",\"reason\":\"teste csrf\"}";
        String path = "/api/v1/users/" + balcao.userId() + "/status";

        assertThat(browser.post(path, body, Map.of("If-Match", "\"1\"")).statusCode()).isEqualTo(403);
        assertThat(browser.post(path, body, Map.of("If-Match", "\"1\"", "X-XSRF-TOKEN", "forjado")).statusCode())
                .isEqualTo(403);
        assertThat(browser.post(path, body, csrf()).statusCode()).isEqualTo(200);
    }

    @Test
    void balconistaNaoEntraNoSiteDoPatrao() {
        Person balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão Teste");
        login(balcao);
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(403);
    }

    @Test
    void logoutEncerraASessaoEDevolveLogoutDoIdp() {
        login(patrao);
        browser.get("/bff/session");
        HttpResponse<String> out = browser.post("/bff/logout", "{}", Map.of("X-XSRF-TOKEN",
                browser.cookie("XSRF-TOKEN")));
        assertThat(out.statusCode()).isEqualTo(200);
        String url = JSON.readTree(out.body()).path("logout_url").asString();
        assertThat(url).startsWith(IDP.base() + "/logout?client_id=engenheiro-web").contains("id_token_hint=");
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(401);
    }

    @Test
    void logoutSemCsrfERecusado() {
        login(patrao);
        assertThat(browser.post("/bff/logout", "{}", Map.of()).statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(200);
    }

    @Test
    void tokenExpiradoERenovadoNoServidorERefreshRevogadoEncerraSessao() {
        IDP.accessTokenTtlSeconds = 1; // abaixo da margem de 60 s: o servidor renova a cada chamada
        login(patrao);
        int before = IDP.refreshCalls.get();
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(200);
        assertThat(IDP.refreshCalls.get()).isGreaterThan(before);

        IDP.revokeAllRefreshTokens(); // ex.: conta desativada no IdP
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(401);
    }

    @Test
    void bloqueioNoSistemaDerrubaSessaoWebNaProximaChamada() {
        Person socio = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Sócio Teste");
        login(socio);
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(200);
        // patrão bloqueia o sócio usando bearer (app) — mesma política
        Api api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        assertThat(api.post("/api/v1/users/" + socio.userId() + "/status", patrao.tokenFor("engenheiro-gestao"),
                Map.of("status", "DISABLED", "reason", "saída"), "\"1\"").status()).isEqualTo(200);
        assertThat(browser.get("/api/v1/me/capabilities").statusCode()).isEqualTo(403);
    }

    @Test
    void parametrosDeLoginNaoTrazemSegredo() {
        HttpResponse<String> start = browser.get("/oauth2/authorization/engenheiro");
        assertThat(start.headers().firstValue("Location").orElseThrow()).doesNotContain(StubIdp.CLIENT_SECRET);
        assertThat(List.of(start.body())).noneMatch(b -> b.contains(StubIdp.CLIENT_SECRET));
    }
}
