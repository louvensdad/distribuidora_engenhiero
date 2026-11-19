package br.com.engenheiro.api.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.engenheiro.api.shared.ops.LogSanitizer;
import br.com.engenheiro.api.shared.ops.ProductionGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** F16 (OPS-01/03/05): guarda de produção e limpeza de logs, sem subir contexto. */
class ProductionHardeningTest {

    static MockEnvironment prodOk() {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles("prod");
        e.setProperty("spring.datasource.url", "jdbc:postgresql://db.interno:5432/engenheiro");
        e.setProperty("spring.datasource.password", "segredo-vindo-do-cofre");
        e.setProperty("spring.flyway.locations", "classpath:db/migration");
        e.setProperty("engenheiro.security.oidc.issuer", "https://login.exemplo.invalid/realms/engenheiro");
        e.setProperty("engenheiro.security.oidc.jwk-set-uri", "https://login.exemplo.invalid/realms/engenheiro/certs");
        e.setProperty("engenheiro.web.app-url", "https://gestao.exemplo.invalid/");
        e.setProperty("engenheiro.web.client-secret", "segredo-do-site");
        e.setProperty("engenheiro.storage.local-dir", "/dados/objetos");
        e.setProperty("engenheiro.log.pepper", "x".repeat(40));
        return e;
    }

    @Test
    void producaoCompletaSobe() {
        assertThat(ProductionGuard.problems(prodOk())).isEmpty();
        ProductionGuard.check(prodOk());
    }

    @Test
    void perfilLocalNaoEGuardado() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThat(ProductionGuard.problems(local)).isEmpty();
    }

    @Test
    void producaoComVestigioLocalOuSemParametroERecusadaSemExporSegredo() {
        MockEnvironment e = prodOk();
        e.setActiveProfiles("prod", "local");
        e.setProperty("engenheiro.seed-local.enabled", "true");
        e.setProperty("spring.flyway.locations", "classpath:db/migration,classpath:db/seed-local");
        e.setProperty("spring.datasource.password", "");
        e.setProperty("engenheiro.security.oidc.issuer", "http://localhost:8180/realms/engenheiro");
        e.setProperty("server.servlet.session.cookie.secure", "false");
        e.setProperty("engenheiro.log.pepper", "curto");
        List<String> p = ProductionGuard.problems(e);
        assertThat(p).anyMatch(s -> s.contains("perfil 'local'"))
                .anyMatch(s -> s.contains("seed-local.enabled"))
                .anyMatch(s -> s.contains("db/seed-local"))
                .anyMatch(s -> s.contains("ENGENHEIRO_DB_PASSWORD"))
                .anyMatch(s -> s.equals("ENGENHEIRO_OIDC_ISSUER sem HTTPS"))
                .anyMatch(s -> s.equals("ENGENHEIRO_OIDC_ISSUER aponta para endereço local"))
                .anyMatch(s -> s.contains("Secure"))
                .anyMatch(s -> s.contains("ENGENHEIRO_LOG_PEPPER"));
        assertThatThrownBy(() -> ProductionGuard.check(e)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nada foi migrado").hasMessageNotContaining("segredo-do-site");
        MockEnvironment h = prodOk();
        h.setActiveProfiles("homologacao");
        h.setProperty("engenheiro.storage.local-dir", "");
        assertThat(ProductionGuard.problems(h)).containsExactly("ENGENHEIRO_STORAGE_DIR ausente (documentos e anexos)");
    }

    @Test
    void provisionamentoPelaRedeInternaSoComNomeDeServicoOuHttps() {
        for (String ok : List.of("http://keycloak:8080/realms/engenheiro", "https://idp.interno.example/realms/engenheiro")) {
            MockEnvironment e = prodOk();
            e.setProperty("engenheiro.provisioner.internal-url", ok);
            assertThat(ProductionGuard.problems(e)).as(ok).isEmpty();
        }
        for (String ruim : List.of("http://idp.example.com/realms/engenheiro", "http://localhost:8180/realms/engenheiro",
                "http://127.0.0.1:8080/realms/engenheiro", "ftp://keycloak/realms/engenheiro")) {
            MockEnvironment e = prodOk();
            e.setProperty("engenheiro.provisioner.internal-url", ruim);
            assertThat(ProductionGuard.problems(e)).as(ruim).singleElement().asString().startsWith("ENGENHEIRO_PROVISIONER_INTERNAL_URL");
        }
    }

    @Test
    void seedFicticioSoExisteForaDasMigrationsDeProducao() throws Exception {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path f : files.toList()) {
                String sql = Files.readString(f);
                assertThat(sql).as(f.toString()).doesNotContainIgnoringCase("fictíci").doesNotContainIgnoringCase("seed");
            }
        }
        assertThat(Files.readString(Path.of("src/main/resources/application-prod.yaml")))
                .doesNotContain("seed-local,").contains("locations: classpath:db/migration");
    }

    @Test
    void sanitizadorRemoveTokenSegredoDocumentoEmailEDetalheDoBanco() {
        String raw = "ERROR: duplicate key value violates unique constraint \"customers_doc\" Detail: Key (personal_document)"
                + "=(11222333000181) already exists. cpf 123.456.789-09 conta 0012345678 email ana@exemplo.com "
                + "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhYmMifQ.c2lnbmF0dXJl password=abc123 client_secret: xyz";
        String clean = LogSanitizer.clean(raw);
        assertThat(clean).doesNotContain("11222333000181").doesNotContain("123.456.789-09").doesNotContain("0012345678")
                .doesNotContain("ana@exemplo.com").doesNotContain("eyJhbGci").doesNotContain("abc123").doesNotContain("xyz")
                .contains("Key (personal_document)=(•••)").contains("duplicate key");
        Throwable scrubbed = LogSanitizer.scrub(new IllegalStateException("cpf 12345678909", new RuntimeException("senha=topo")));
        assertThat(scrubbed.getMessage()).contains("IllegalStateException").doesNotContain("12345678909");
        assertThat(scrubbed.getCause().getMessage()).doesNotContain("topo");
        assertThat(scrubbed.getStackTrace()).isNotEmpty();
    }
}
