package br.com.engenheiro.api.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * F12 (APP-E-01..03): app Entregas com o client próprio — motorista não abre viagem alheia (ID/deep link), Pix pago não
 * é cobrado de novo, reenvio da entrega não baixa estoque duas vezes, acesso revogado no meio da viagem para na hora e
 * o motorista só recebe os dados mínimos da viagem.
 */
@IntegrationTest
class EntregasAppIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person coord;
    Person motoristaA;
    Person motoristaB;
    String product;
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        coord = fx.person(org, org.branch1(), RoleTemplate.COORDENADOR_ENTREGAS, "Marcos da Coordenação");
        motoristaA = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Carlos Motorista");
        motoristaB = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Roberto Motorista");
        fx.grant(coord, "trips", "assign", "SECTOR", null);
        fx.grant(coord, "vehicles", "create", "BRANCH", null);
        for (Person m : List.of(motoristaA, motoristaB)) {
            fx.grant(m, "payments", "confirm", "BRANCH", null);
            fx.grant(m, "payments", "create", "BRANCH", null);
            fx.grant(m, "own_cash", "view", "OWN", null);
            fx.grant(m, "own_cash", "create", "OWN", null);
            fx.grant(m, "trips", "close", "ASSIGNED", null);
        }
        assertThat(coord.client()).isEqualTo(AppClients.ENTREGAS);
        assertThat(motoristaA.client()).isEqualTo(AppClients.ENTREGAS);
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "Cana caiana", "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        product = p.json().path("id").asString();
        api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "7.50"), p.header("ETag"));
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio"), (String) null).json().path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup, "lines",
                List.of(Map.of("product_id", product, "quantity_received", "200", "unit_cost", "3.00"))), key()).json();
        api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        customer = api.post("/api/v1/customers", patrao.token(), Map.of("name", "Mercado Fictício", "address", "Rua Exemplo, 100",
                "personal_document", "11222333000181"), (String) null).json().path("id").asString();
        api.postWith("/api/v1/cash-sessions", motoristaA.token(), Map.of("opening_float", "0.00"), key());
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    static Map<String, String> ifMatch(long version) {
        return Map.of("If-Match", "\"" + version + "\"");
    }

    String deliveryOrder(String qty, String agreed) {
        Map<String, Object> delivery = new HashMap<>(Map.of("address", "Rua Exemplo, 100 — Centro", "agreed_payment", agreed));
        Api.Res o = api.postWith("/api/v1/orders", coord.token(), Map.of("type", "DELIVERY", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", qty)), "delivery", delivery), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        return o.json().path("id").asString();
    }

    String vehicle() {
        java.util.Random rnd = new java.util.Random();
        String plate = "" + (char) ('A' + rnd.nextInt(26)) + (char) ('A' + rnd.nextInt(26)) + (char) ('A' + rnd.nextInt(26))
                + rnd.nextInt(10) + (char) ('A' + rnd.nextInt(26)) + String.format("%02d", rnd.nextInt(100)); // padrão Mercosul fictício
        Api.Res v = api.postWith("/api/v1/vehicles", coord.token(), Map.of("plate", plate, "model", "Fiorino",
                "capacity_quantity", "300", "capacity_unit", "DUZIA", "odometer_km", "45200"), key());
        assertThat(v.status()).as(v.body()).isEqualTo(201);
        return v.json().path("id").asString();
    }

    /** Despacho pelo app (coordenação): cria viagem, atribui veículo, motorista e pedidos; o motorista sai. */
    JsonNode dispatched(Person driver, String... orders) {
        JsonNode t = api.postWith("/api/v1/trips", coord.token(), Map.of(), key()).json();
        Api.Res a = api.postWith("/api/v1/trips/" + t.path("id").asString() + "/assignments", coord.token(),
                Map.of("vehicle_id", vehicle(), "driver_id", driver.userId().toString(), "order_ids", List.of(orders)),
                ifMatch(t.path("version").asLong()));
        assertThat(a.status()).as(a.body()).isEqualTo(200);
        Api.Res d = api.postWith("/api/v1/trips/" + t.path("id").asString() + "/depart", driver.token(),
                Map.of("odometer", "45210"), ifMatch(a.json().path("version").asLong()));
        assertThat(d.status()).as(d.body()).isEqualTo(200);
        return d.json();
    }

    static String stopOf(JsonNode trip, String orderId) {
        for (JsonNode s : trip.path("stops")) {
            if (s.path("order").path("id").asString().equals(orderId)) {
                return s.path("id").asString();
            }
        }
        throw new AssertionError("parada não encontrada");
    }

    BigDecimal physical() {
        return jdbc.sql("SELECT coalesce(sum(physical), 0) FROM inventory_balances WHERE product_id = :p")
                .param("p", UUID.fromString(product)).query(BigDecimal.class).single();
    }

    // ---------- Critério: motorista não abre outra viagem alterando UUID ou deep link ----------
    @Test
    void motoristaNaoAbreViagemAlheiaPorIdNemPelosRecursosDela() {
        String oA = deliveryOrder("2", "CASH");
        String oB = deliveryOrder("2", "CASH");
        JsonNode tA = dispatched(motoristaA, oA);
        JsonNode tB = dispatched(motoristaB, oB);
        String b = tB.path("id").asString();
        String stopB = stopOf(tB, oB);
        // Lista: só a dele. Detalhe e todos os sub-recursos da viagem alheia: 404 (indistinguível de inexistente).
        assertThat(api.get("/api/v1/trips", motoristaA.token()).json().path("items").toString()).doesNotContain(b);
        for (String path : List.of("/api/v1/trips/" + b, "/api/v1/trips/" + b + "/history",
                "/api/v1/trips/" + b + "/stops/" + stopB + "/attachments")) {
            assertThat(api.get(path, motoristaA.token()).status()).as(path).isEqualTo(404);
        }
        assertThat(api.get("/api/v1/trips/" + b + "/costs", motoristaA.token()).status()).isIn(403, 404);
        assertThat(api.postWith("/api/v1/trips/" + b + "/deliveries", motoristaA.token(), Map.of("stop_id", stopB,
                "recipient_name", "X"), key()).status()).isEqualTo(404);
        assertThat(api.postWith("/api/v1/trips/" + b + "/incidents", motoristaA.token(), Map.of("stop_id", stopB, "kind",
                "ABSENT", "description", "x"), key()).status()).isEqualTo(404);
        assertThat(api.postWith("/api/v1/trips/" + b + "/return", motoristaA.token(), Map.of("odometer", "45300",
                "cash_declared", "0"), key(tB.path("version").asLong())).status()).isEqualTo(404);
        // Parada da viagem alheia usada na PRÓPRIA viagem: recusada, nada entregue.
        Api.Res cross = api.postWith("/api/v1/trips/" + tA.path("id").asString() + "/deliveries", motoristaA.token(),
                Map.of("stop_id", stopB, "recipient_name", "X"), key());
        assertThat(cross.status()).isIn(404, 422);
        assertThat(api.get("/api/v1/orders/" + oB, motoristaB.token()).status()).isEqualTo(403); // pedido só pela viagem
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillments WHERE order_id = :o").param("o", UUID.fromString(oB))
                .query(Long.class).single()).isZero();
        // Dados mínimos: motorista não lista clientes nem pedidos, e a viagem não traz documento do cliente nem custo.
        assertThat(api.get("/api/v1/customers", motoristaA.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/orders", motoristaA.token()).status()).isEqualTo(403);
        String own = api.get("/api/v1/trips/" + tA.path("id").asString(), motoristaA.token()).body();
        assertThat(own).contains("Rua Exemplo, 100").doesNotContain("11222333000181").doesNotContain("unit_cost");
    }

    // ---------- Critério: Pix pago não é cobrado novamente ----------
    @Test
    void pixPagoNaoECobradoDeNovoNaEntrega() {
        String o = deliveryOrder("4", "PIX");
        JsonNode pay = api.postWith("/api/v1/orders/" + o + "/payments", patrao.token(), Map.of("parts",
                List.of(Map.of("method", "PIX", "amount", "30.00", "provider_reference", "E2E-PIX-FICTICIO-1"))), key()).json();
        String pid = pay.path("payments").get(0).path("id").asString();
        Api.Res conf = api.postWith("/api/v1/payments/" + pid + "/confirm", patrao.token(), Map.of("note", "conferido no extrato"),
                key(pay.path("payments").get(0).path("version").asLong()));
        assertThat(conf.status()).as(conf.body()).isEqualTo(200);
        JsonNode t = dispatched(motoristaA, o);
        String stop = stopOf(t, o);
        // A parada mostra o pedido pago (o app exibe "não cobrar").
        JsonNode stopOrder = t.path("stops").get(0).path("order");
        assertThat(stopOrder.path("payment_state").asString()).isEqualTo("PAID");
        assertThat(stopOrder.toString()).contains("\"balance_due\":\"0.00\"");
        // Tentar cobrar de novo (na entrega ou antes): recusado, nada entra no caixa.
        Api.Res again = api.postWith("/api/v1/trips/" + t.path("id").asString() + "/deliveries", motoristaA.token(),
                Map.of("stop_id", stop, "recipient_name", "Gerente", "payment", Map.of("parts", List.of(Map.of("method", "CASH",
                        "amount", "30.00", "presented_cash", "50.00")))), key());
        assertThat(again.status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/trips/" + t.path("id").asString() + "/stops/" + stop + "/payments", motoristaA.token(),
                Map.of("parts", List.of(Map.of("method", "CASH", "amount", "30.00", "presented_cash", "30.00"))), key()).status())
                .isEqualTo(422);
        // Entrega sem cobrança: concluída; um único pagamento no pedido; caixa do motorista sem entrada.
        Api.Res ok = api.postWith("/api/v1/trips/" + t.path("id").asString() + "/deliveries", motoristaA.token(),
                Map.of("stop_id", stop, "recipient_name", "Gerente"), key());
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :o").param("o", UUID.fromString(o))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(api.get("/api/v1/cash-sessions/current", motoristaA.token()).json().path("session").path("cash_in").asString())
                .isEqualTo("0.00");
    }

    // ---------- Critério: reenvio da entrega não baixa estoque duas vezes ----------
    @Test
    void reenvioDaEntregaNaoBaixaEstoqueDuasVezes() throws Exception {
        String o = deliveryOrder("10", "CASH");
        JsonNode t = dispatched(motoristaA, o);
        String stop = stopOf(t, o);
        BigDecimal before = physical();
        Map<String, String> k = key();
        Map<String, Object> body = Map.of("stop_id", stop, "recipient_name", "Ana", "payment", Map.of("parts",
                List.of(Map.of("method", "CASH", "amount", "75.00", "presented_cash", "100.00"))));
        // O app reenviou 4 vezes (rede instável), com a MESMA chave, ao mesmo tempo.
        var pool = Executors.newFixedThreadPool(4);
        List<Future<Api.Res>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<Api.Res> c = () -> api.postWith("/api/v1/trips/" + t.path("id").asString() + "/deliveries", motoristaA.token(), body, k);
            fs.add(pool.submit(c));
        }
        List<Integer> st = new ArrayList<>();
        for (Future<Api.Res> f : fs) {
            st.add(f.get().status());
        }
        pool.shutdown();
        assertThat(st).contains(200).allMatch(s -> s == 200 || s == 409);
        // Outra chave depois de entregue: recusada pelo estado da parada.
        assertThat(api.postWith("/api/v1/trips/" + t.path("id").asString() + "/deliveries", motoristaA.token(), body, key())
                .status()).isEqualTo(409);
        UUID oid = UUID.fromString(o);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillments WHERE order_id = :o").param("o", oid).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :o").param("o", oid).query(Long.class).single()).isEqualTo(1);
        assertThat(before.subtract(physical())).isEqualByComparingTo("10");
        assertThat(api.get("/api/v1/cash-sessions/current", motoristaA.token()).json().path("session").path("cash_in").asString())
                .isEqualTo("75.00");
    }

    // ---------- Acesso revogado no meio da viagem: a próxima ação já é recusada ----------
    @Test
    void acessoRevogadoNoMeioDaViagemParaNaHora() {
        String o = deliveryOrder("2", "CASH");
        JsonNode t = dispatched(motoristaA, o);
        JsonNode user = null;
        for (JsonNode u : api.get("/api/v1/users", patrao.token()).json().path("items")) {
            if (u.path("id").asString().equals(motoristaA.userId().toString())) {
                user = u;
            }
        }
        Api.Res block = api.postWith("/api/v1/users/" + motoristaA.userId() + "/status", patrao.token(),
                Map.of("status", "DISABLED", "reason", "Saiu da empresa (fictício)"), ifMatch(user.path("version").asLong()));
        assertThat(block.status()).as(block.body()).isEqualTo(200);
        assertThat(api.get("/api/v1/trips/" + t.path("id").asString(), motoristaA.token()).status()).isIn(401, 403);
        assertThat(api.postWith("/api/v1/trips/" + t.path("id").asString() + "/deliveries", motoristaA.token(),
                Map.of("stop_id", stopOf(t, o), "recipient_name", "X"), key()).status()).isIn(401, 403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillments WHERE order_id = :o").param("o", UUID.fromString(o))
                .query(Long.class).single()).isZero();
        // A viagem continua visível à coordenação, com o mesmo estado, para reatribuir/encerrar.
        assertThat(api.get("/api/v1/trips/" + t.path("id").asString(), coord.token()).json().path("state").asString())
                .isEqualTo("DEPARTED");
    }
}
