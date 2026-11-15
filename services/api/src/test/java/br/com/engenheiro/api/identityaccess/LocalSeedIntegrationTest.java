package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** Esquema + seed fictício local do zero; seed só com migrations repetíveis (nunca "fora de ordem"). */
@IntegrationTest
@TestPropertySource(properties = {"spring.flyway.locations=classpath:db/migration,classpath:db/seed-local",
        "engenheiro.seed-local.enabled=true"})
class LocalSeedIntegrationTest {
    @Autowired
    JdbcClient jdbc;

    @Test
    void seedLocalUsaSomenteMigrationsRepetiveis() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/seed-local"))) {
            assertThat(files.map(p -> p.getFileName().toString())).allMatch(n -> n.startsWith("R__"));
        }
    }

    @Test
    void seedFicticioSobeDoZeroComModelosAplicados() {
        assertThat(jdbc.sql("SELECT name FROM organizations WHERE id = '0193a000-0000-7000-8000-000000000001'")
                .query(String.class).single()).isEqualTo("Distribuidora de Cana Engenheiro Ltda");
        assertThat(jdbc.sql("SELECT count(*) FROM memberships WHERE organization_id = '0193a000-0000-7000-8000-000000000001'")
                .query(Integer.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM memberships m WHERE NOT m.is_admin
                           AND NOT EXISTS (SELECT 1 FROM permission_grants g WHERE g.membership_id = m.id)
                        """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_users WHERE display_name LIKE '%(%'").query(Integer.class)
                .single()).isZero();
    }
}
