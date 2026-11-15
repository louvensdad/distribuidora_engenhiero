package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Dois administradores bloqueando um ao outro ao mesmo tempo nunca zeram os administradores ativos. */
@IntegrationTest
class ConcurrencyIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;

    @RepeatedTest(5)
    void bloqueiosCruzadosConcorrentesPreservamUmAdministrador() throws Exception {
        Api api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        Org org = fx.org();
        Person a = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Admin A");
        Person b = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Admin B");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Api.Res> ab = CompletableFuture.supplyAsync(() -> {
            await(start);
            return api.post("/api/v1/users/" + b.userId() + "/status", a.token(),
                    Map.of("status", "DISABLED", "reason", "concorrência"), "\"1\"");
        });
        CompletableFuture<Api.Res> ba = CompletableFuture.supplyAsync(() -> {
            await(start);
            return api.post("/api/v1/users/" + a.userId() + "/status", b.token(),
                    Map.of("status", "DISABLED", "reason", "concorrência"), "\"1\"");
        });
        start.countDown();
        int s1 = ab.get().status();
        int s2 = ba.get().status();

        int active = jdbc.sql("SELECT count(*) FROM memberships WHERE organization_id = :o AND is_admin "
                + "AND status = 'ACTIVE'").param("o", org.id()).query(Integer.class).single();
        assertThat(active).isEqualTo(1);
        // Exatamente um bloqueio vence; o outro é recusado (ator já bloqueado → 403, ou último admin → 409).
        assertThat(new int[]{s1, s2}).containsOnlyOnce(200);
        assertThat(s1 == 200 ? s2 : s1).isIn(403, 409);
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
