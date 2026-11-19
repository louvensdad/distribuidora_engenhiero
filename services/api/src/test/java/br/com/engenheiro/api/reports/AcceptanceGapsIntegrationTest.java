package br.com.engenheiro.api.reports;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** F17 — cenários de aceite sem teste dedicado até aqui: B01 (reservas concorrentes) e S07 (exportação revogada). */
@IntegrationTest
class AcceptanceGapsIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        customer = api.post("/api/v1/customers", patrao.token(), Map.of("name", "Mercado Fictício"), (String) null).json()
                .path("id").asString();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    String productWithStock(String qty) {
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Cana", "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        assertThat(p.status()).as(p.body()).isEqualTo(201);
        String id = p.json().path("id").asString();
        api.post("/api/v1/products/" + id + "/prices", patrao.token(), Map.of("price", "20.00"), p.header("ETag"));
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio"), (String) null).json().path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", id, "quantity_received", qty, "unit_cost", "7.50"))), key()).json();
        Api.Res c = api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null,
                Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"1\""));
        assertThat(c.status()).as(c.body()).isEqualTo(200);
        return id;
    }

    // B01: físico 100, reservado 0. Reservar 60 e 50 ao mesmo tempo: só uma combinação cabe, nunca disponível negativo.
    @RepeatedTest(5)
    void reservasConcorrentesDe60E50SobreEstoque100SoUmaPassa() throws Exception {
        String product = productWithStock("100");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Api.Res>> res = new ArrayList<>();
        for (String q : List.of("60", "50")) {
            res.add(pool.submit(() -> {
                go.await();
                return api.postWith("/api/v1/orders", patrao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                        "items", List.of(Map.of("product_id", product, "quantity", q))), key());
            }));
        }
        go.countDown();
        List<Integer> status = new ArrayList<>();
        for (Future<Api.Res> f : res) {
            status.add(f.get().status());
        }
        pool.shutdown();
        assertThat(status).as(status.toString()).containsOnlyOnce(201);
        Api.Res refused = res.get(0).get().status() == 201 ? res.get(1).get() : res.get(0).get();
        assertThat(refused.status()).as(refused.body()).isBetween(409, 422);
        record Bal(java.math.BigDecimal physical, java.math.BigDecimal reserved) {
        }
        Bal b = jdbc.sql("SELECT physical, reserved FROM inventory_balances WHERE product_id = :p").param("p", UUID.fromString(product))
                .query(Bal.class).single();
        assertThat(b.physical()).isEqualByComparingTo("100");
        assertThat(b.reserved()).isIn(new java.math.BigDecimal("60.000000"), new java.math.BigDecimal("50.000000"));
        assertThat(b.physical().subtract(b.reserved()).signum()).isGreaterThanOrEqualTo(0);
        int active = jdbc.sql("SELECT count(*) FROM stock_reservations WHERE product_id = :p AND state = 'ACTIVE'")
                .param("p", UUID.fromString(product)).query(Integer.class).single();
        assertThat(active).isEqualTo(1);
    }

    // S07: exportação depois da revogação é recusada na hora; o arquivo nunca fica em endereço público.
    @Test
    void exportacaoRevalidaPermissaoNaHoraEArquivoNaoEPublico() {
        Person gerente = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Gerente");
        fx.grant(gerente, "reports", "view", "ORGANIZATION", null);
        fx.grant(gerente, "reports", "export", "ORGANIZATION", null);
        String q = "/api/v1/reports/overview/export?format=csv&period=MONTH&date=" + LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        Api.Res ok = api.getBytes(q, gerente.token());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.header("Cache-Control")).contains("no-store");
        assertThat(ok.header("Content-Disposition")).startsWith("attachment");
        // Revogada a exportação (sessão e token continuam os mesmos): o próximo arquivo é negado e registrado.
        jdbc.sql("""
                        UPDATE permission_grants SET revoked_at = now()
                         WHERE membership_id = (SELECT id FROM memberships WHERE user_id = :u) AND resource = 'reports' AND action = 'export'
                        """).param("u", gerente.userId()).update();
        Api.Res denied = api.getBytes(q, gerente.token());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain("Receita líquida");
        // Sem sessão nenhuma, o mesmo endereço não entrega arquivo.
        assertThat(api.getBytes(q, null).status()).isEqualTo(401);
    }
}
