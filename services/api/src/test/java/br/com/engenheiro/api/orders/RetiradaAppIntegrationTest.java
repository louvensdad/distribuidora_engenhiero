package br.com.engenheiro.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * F11 (APP-R-01..03): o app Retirada com o client próprio — balcão e recebedor no MESMO app com funções separadas
 * pelo servidor; pedido do balcão visto pelo patrão (site e app Patrão) com o autor e o app corretos; nova tentativa
 * depois de perder a resposta não duplica pagamento nem retirada; "meus pedidos" por busca e período.
 */
@IntegrationTest
class RetiradaAppIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person balcao;
    Person recebedor;
    Person contador;
    String product;
    String supplier;
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Paula do Balcão");
        recebedor = fx.person(org, org.branch1(), RoleTemplate.RECEBIMENTO, "João do Recebimento");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Fernanda Contadora");
        // O que o dono libera ao balcão no mapa de acessos (receber, caixa próprio, entregar o que vendeu).
        for (String a : List.of("view", "create", "confirm")) {
            fx.grant(balcao, "payments", a, "BRANCH", null);
        }
        for (String a : List.of("view", "create", "close")) {
            fx.grant(balcao, "own_cash", a, "OWN", null);
        }
        fx.grant(balcao, "orders.pickup", "confirm", "OWN", null);
        assertThat(balcao.client()).isEqualTo(AppClients.RETIRADA);
        assertThat(recebedor.client()).isEqualTo(AppClients.RETIRADA);

        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA-R" + UUID.randomUUID().toString()
                .substring(0, 5), "name", "Cana caiana fictícia", "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        product = p.json().path("id").asString();
        api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "7.50"), p.header("ETag"));
        supplier = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio Fictício"), (String) null).json()
                .path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", supplier,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "100", "unit_cost", "3.00"))), key()).json();
        assertThat(api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null,
                key(1)).status()).isEqualTo(200);
        customer = api.post("/api/v1/customers", balcao.token(), Map.of("name", "Ana Fictícia"), (String) null).json()
                .path("id").asString();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    JsonNode order(Person who, String qty) {
        Api.Res o = api.postWith("/api/v1/orders", who.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", qty))), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        return o.json();
    }

    // ---------- Critério: pedido do balcão aparece no site e no app Patrão com o autor correto ----------
    @Test
    void pedidoDoBalcaoApareceNoSiteENoAppPatraoComAutorEApp() {
        assertThat(api.postWith("/api/v1/cash-sessions", balcao.token(), Map.of("opening_float", "50.00"), key()).status())
                .isEqualTo(201);
        JsonNode o = order(balcao, "10");                                  // 10 dz × 7,50 = 75,00 (preço do servidor)
        assertThat(o.path("total").asString()).isEqualTo("75.00");
        String id = o.path("id").asString();
        Api.Res pay = api.postWith("/api/v1/orders/" + id + "/payments", balcao.token(), Map.of("parts",
                List.of(Map.of("method", "CASH", "amount", "75.00", "presented_cash", "100.00"))), key());
        assertThat(pay.status()).as(pay.body()).isEqualTo(200);
        assertThat(pay.json().path("payments").get(0).path("change_given").asString()).isEqualTo("25.00");
        assertThat(api.postWith("/api/v1/orders/" + id + "/fulfillments", balcao.token(), null, key()).status()).isEqualTo(200);

        // Site do patrão (cliente web) e app Patrão (cliente gestao): mesmo pedido, autor = balcão.
        for (String token : List.of(patrao.token(), patrao.tokenFor(AppClients.GESTAO))) {
            JsonNode seen = api.get("/api/v1/orders/" + id, token).json();
            assertThat(seen.path("created_by_name").asString()).isEqualTo("Paula do Balcão");
            assertThat(seen.path("state").asString()).isEqualTo("FULFILLED");
            assertThat(seen.path("payment_state").asString()).isEqualTo("PAID");
            List<JsonNode> hist = new ArrayList<>();
            api.get("/api/v1/orders/" + id + "/history", token).json().path("items").forEach(hist::add);
            assertThat(hist).extracting(h -> h.path("action").asString())
                    .contains("orders.create", "payments.register", "orders.fulfill");
            assertThat(hist).allMatch(h -> h.path("actor_name").asString().equals("Paula do Balcão")
                    && h.path("client_id").asString().equals(AppClients.RETIRADA));
        }
        // Na central do setor de Retirada também, pelo app de origem.
        List<JsonNode> counter = new ArrayList<>();
        api.get("/api/v1/audit-events?sector=COUNTER&entity_id=" + id, patrao.tokenFor(AppClients.GESTAO)).json()
                .path("items").forEach(counter::add);
        assertThat(counter).isNotEmpty().allMatch(e -> e.path("actor_user_id").asString().equals(balcao.userId().toString()));
        // Caixa do turno: só o valor do pedido entra (o troco não).
        JsonNode cash = api.get("/api/v1/cash-sessions/current", balcao.token()).json().path("session");
        assertThat(cash.path("expected").asString()).isEqualTo("125.00");
    }

    // ---------- Balconista e recebedor no MESMO app, com funções separadas pelo servidor ----------
    @Test
    void balconistaERecebedorNoMesmoAppComFuncoesSeparadas() {
        // Recebedor: registra o que chegou, sem custo; não vende nem vê pedidos/caixa.
        Map<String, Object> line = new HashMap<>(Map.of("product_id", product, "quantity_expected", "50", "quantity_received", "48"));
        Api.Res d = api.postWith("/api/v1/inventory/receipts", recebedor.token(), Map.of("supplier_id", supplier, "lines",
                List.of(line)), key());
        assertThat(d.status()).as(d.body()).isEqualTo(201);
        assertThat(api.get("/api/v1/orders", recebedor.token()).status()).isEqualTo(403);
        assertThat(api.postWith("/api/v1/orders", recebedor.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", "1"))), key()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/cash-sessions/current", recebedor.token()).status()).isEqualTo(403);
        // Custo do fornecedor bloqueado para quem recebe: o recebimento conferido pelo patrão aparece sem custo.
        List<JsonNode> receipts = new ArrayList<>();
        api.get("/api/v1/inventory/receipts?status=CONFIRMED", recebedor.token()).json().path("items").forEach(receipts::add);
        assertThat(receipts).isNotEmpty();
        for (JsonNode r : receipts) {
            assertThat(r.path("total_cost").isNull()).isTrue();
            assertThat(r.path("masked_fields").toString()).contains("total_cost");
            r.path("lines").forEach(l -> assertThat(l.path("unit_cost").isNull()).isTrue());
        }
        assertThat(api.get("/api/v1/inventory/receipts?status=CONFIRMED", recebedor.token()).body()).doesNotContain("3.00");

        // Balcão: vende e recebe, mas não registra nem vê recebimento de fornecedor (nem custo), relatório ou saldo da empresa.
        assertThat(api.get("/api/v1/inventory/receipts", balcao.token()).status()).isEqualTo(403);
        assertThat(api.postWith("/api/v1/inventory/receipts", balcao.token(), Map.of("supplier_id", supplier, "lines",
                List.of(line)), key()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/suppliers", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/reports/overview", balcao.token()).status()).isEqualTo(403);
        // Quem confirma pagamento escolhe a conta pelo nome, sem nenhum saldo da empresa (F06).
        JsonNode accs = api.get("/api/v1/finance/accounts", balcao.token()).json().path("items");
        assertThat(accs).isNotEmpty();
        accs.forEach(a -> assertThat(a.path("balance").isNull()).isTrue());
        assertThat(api.get("/api/v1/reports/summary", balcao.token()).status()).isEqualTo(403);
        Api.Res s = api.get("/api/v1/search?q=fict", balcao.token());
        assertThat(s.json().path("searched").toString()).doesNotContain("suppliers");
        // Estoque do balcão: quantidades, nunca custo médio.
        JsonNode bal = api.get("/api/v1/inventory/balances?limit=100", balcao.token()).json();
        assertThat(bal.toString()).doesNotContain("3.000000").doesNotContain("\"average_cost\":\"");

        // Só o app Retirada: as mesmas contas não entram por outro app, nem patrão/contador entram no Retirada.
        assertThat(api.get("/api/v1/me/capabilities", balcao.tokenFor(AppClients.GESTAO)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/me/capabilities", recebedor.tokenFor(AppClients.ENTREGAS)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/me/capabilities", patrao.tokenFor(AppClients.RETIRADA)).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/me/capabilities", contador.tokenFor(AppClients.RETIRADA)).status()).isEqualTo(403);
    }

    // ---------- Desconexão: repetir depois de perder a resposta não duplica pagamento, retirada nem pedido ----------
    @Test
    void novaTentativaAposPerderARespostaNaoDuplica() throws Exception {
        api.postWith("/api/v1/cash-sessions", balcao.token(), Map.of("opening_float", "0.00"), key());
        Map<String, String> orderKey = key();
        Map<String, Object> body = Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", "4")));
        String id = api.postWith("/api/v1/orders", balcao.token(), body, orderKey).json().path("id").asString();
        assertThat(api.postWith("/api/v1/orders", balcao.token(), body, orderKey).json().path("id").asString()).isEqualTo(id);

        // O app reenviou o pagamento (mesma chave) enquanto o primeiro ainda era processado.
        Map<String, String> payKey = key();
        Map<String, Object> pay = Map.of("parts", List.of(Map.of("method", "CASH", "amount", "30.00", "presented_cash", "50.00")));
        var pool = Executors.newFixedThreadPool(4);
        List<Future<Api.Res>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<Api.Res> c = () -> api.postWith("/api/v1/orders/" + id + "/payments", balcao.token(), pay, payKey);
            fs.add(pool.submit(c));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Api.Res> f : fs) {
            statuses.add(f.get().status());
        }
        pool.shutdown();
        assertThat(statuses).contains(200).allMatch(st -> st == 200 || st == 409);
        Map<String, String> fulKey = key();
        assertThat(api.postWith("/api/v1/orders/" + id + "/fulfillments", balcao.token(), null, fulKey).status()).isEqualTo(200);
        assertThat(api.postWith("/api/v1/orders/" + id + "/fulfillments", balcao.token(), null, fulKey).status()).isEqualTo(200);

        UUID oid = UUID.fromString(id);
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :o").param("o", oid).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillments WHERE order_id = :o").param("o", oid).query(Long.class).single())
                .isEqualTo(1);
        assertThat(api.get("/api/v1/cash-sessions/current", balcao.token()).json().path("session").path("expected").asString())
                .isEqualTo("30.00");
        assertThat(jdbc.sql("SELECT CAST(reserved AS text) FROM inventory_balances WHERE product_id = :p").param("p", UUID.fromString(product))
                .query(String.class).single()).startsWith("0");
    }

    // ---------- R09: meus pedidos por número/cliente e período (dia civil de São Paulo) ----------
    @Test
    void meusPedidosPorBuscaEPeriodo() {
        JsonNode mine = order(balcao, "1");
        order(patrao, "1"); // de outra pessoa: fora do escopo próprio do balcão
        String today = TODAY.toString();
        String tomorrow = TODAY.plusDays(1).toString();
        List<JsonNode> list = new ArrayList<>();
        api.get("/api/v1/orders?from=" + today + "&to=" + tomorrow, balcao.token()).json().path("items").forEach(list::add);
        assertThat(list).extracting(o -> o.path("id").asString()).containsExactly(mine.path("id").asString());
        assertThat(api.get("/api/v1/orders?from=" + tomorrow, balcao.token()).json().path("items")).isEmpty();
        assertThat(api.get("/api/v1/orders?q=" + mine.path("number").asLong(), balcao.token()).json().path("items")).hasSize(1);
        assertThat(api.get("/api/v1/orders?q=ana", balcao.token()).json().path("items")).hasSize(1);
        assertThat(api.get("/api/v1/orders?from=" + today + "&to=" + today, balcao.token()).status()).isEqualTo(422);
    }
}
