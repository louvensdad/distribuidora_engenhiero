package br.com.engenheiro.api.central;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * F14 (APP-P-01..04): app Patrão com o client próprio. Trilha encadeada pedido → estoque → entrega → recebimento →
 * despesa; o dono vê quem alterou cada campo (antes/depois autorizados) e quem não pode ver um campo recebe "•••";
 * ações do dono pelo app ficam com a identidade e o app dele; mudança feita no site chega ao app e versão velha é
 * recusada; evento de que a pessoa não tem permissão não chega a ela.
 */
@IntegrationTest
class PatraoAppIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    String app; // token do patrão pelo app Patrão
    String site; // token do patrão pelo site
    Person coord;
    Person motorista;
    Person balcao;
    String product;
    String customer;
    String receipt;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        app = patrao.tokenFor(AppClients.GESTAO);
        site = patrao.tokenFor(AppClients.WEB);
        coord = fx.person(org, org.branch1(), RoleTemplate.COORDENADOR_ENTREGAS, "Marcos da Coordenação");
        motorista = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Carlos Motorista");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Ana Balcão");
        fx.grant(coord, "trips", "assign", "SECTOR", null);
        fx.grant(coord, "vehicles", "create", "BRANCH", null);
        for (String[] g : List.of(new String[] {"payments", "confirm"}, new String[] {"payments", "create"},
                new String[] {"own_cash", "view"}, new String[] {"own_cash", "create"})) {
            fx.grant(motorista, g[0], g[1], g[0].equals("own_cash") ? "OWN" : "BRANCH", null);
        }
        fx.grant(motorista, "trips", "close", "ASSIGNED", null);
        Api.Res p = api.post("/api/v1/products", app, Map.of("sku", "CANA-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "Cana caiana", "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        assertThat(p.status()).as(p.body()).isEqualTo(201);
        product = p.json().path("id").asString();
        api.post("/api/v1/products/" + product + "/prices", app, Map.of("price", "7.50"), p.header("ETag"));
        String sup = api.post("/api/v1/suppliers", app, Map.of("name", "Sítio"), (String) null).json().path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", app, Map.of("supplier_id", sup, "lines",
                List.of(Map.of("product_id", product, "quantity_received", "200", "unit_cost", "3.40"))), key()).json();
        receipt = r.path("id").asString();
        api.postWith("/api/v1/inventory/receipts/" + receipt + "/confirm", app, null, key(1));
        customer = api.post("/api/v1/customers", app, Map.of("name", "Mercado Fictício", "address", "Rua Exemplo, 100",
                "personal_document", "11222333000181"), (String) null).json().path("id").asString();
        api.postWith("/api/v1/cash-sessions", motorista.token(), Map.of("opening_float", "0.00"), key());
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

    static List<String> field(JsonNode items, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : items) {
            out.add(i.path(name).asString());
        }
        return out;
    }

    // ---------- Entregável: trilha encadeada de pedido, estoque, entrega, recebimento e despesa ----------
    @Test
    void trilhaEncadeadaDoPedidoAteADespesaDaViagem() {
        Map<String, Object> delivery = new HashMap<>(Map.of("address", "Rua Exemplo, 100", "agreed_payment", "CASH"));
        Api.Res o = api.postWith("/api/v1/orders", coord.token(), Map.of("type", "DELIVERY", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", "4")), "delivery", delivery), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        String oid = o.json().path("id").asString();
        Api.Res v = api.postWith("/api/v1/vehicles", coord.token(), Map.of("plate", "FIC1A23", "model", "Fiorino",
                "capacity_quantity", "300", "capacity_unit", "DUZIA", "odometer_km", "45200"), key());
        String vid = v.json().path("id").asString();
        JsonNode t = api.postWith("/api/v1/trips", coord.token(), Map.of(), key()).json();
        String tid = t.path("id").asString();
        JsonNode a = api.postWith("/api/v1/trips/" + tid + "/assignments", coord.token(), Map.of("vehicle_id", vid,
                "driver_id", motorista.userId().toString(), "order_ids", List.of(oid)), ifMatch(t.path("version").asLong())).json();
        JsonNode d = api.postWith("/api/v1/trips/" + tid + "/depart", motorista.token(), Map.of("odometer", "45210"),
                ifMatch(a.path("version").asLong())).json();
        String stop = d.path("stops").get(0).path("id").asString();
        Api.Res fuel = api.postWith("/api/v1/vehicles/" + vid + "/fuel", motorista.token(), Map.of("odometer", "45230",
                "liters", "20", "fuel_type", "DIESEL", "full_tank", true, "amount", "120.00"), key());
        assertThat(fuel.status()).as(fuel.body()).isEqualTo(201);
        Api.Res del = api.postWith("/api/v1/trips/" + tid + "/deliveries", motorista.token(), Map.of("stop_id", stop,
                "recipient_name", "Gerente", "payment", Map.of("parts", List.of(Map.of("method", "CASH", "amount", "30.00",
                        "presented_cash", "30.00")))), key());
        assertThat(del.status()).as(del.body()).isEqualTo(200);

        // O dono, pelo app, vê a cadeia inteira com autor e app de origem.
        JsonNode h = api.get("/api/v1/orders/" + oid + "/history", app).json().path("items");
        List<String> acts = field(h, "action");
        assertThat(acts).contains("orders.create", "trips.assign", "trips.depart", "payments.register", "orders.fulfill",
                "inventory.sale_issue");
        assertThat(field(h, "entity_type")).contains("order", "trip", "vehicle_expense", "product");
        assertThat(h.toString()).contains("Carlos Motorista").contains("Marcos da Coordenação").contains(AppClients.ENTREGAS);
        // Só ações e autores: nenhum valor (custo de compra, preço, troco) vai na trilha.
        assertThat(h.toString()).doesNotContain("3.40").doesNotContain("120.00");
        // Quem vê o pedido mas não viagens, estoque nem despesas recebe só o pedido e os pagamentos.
        Person atendente = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Atendente Sem Frota");
        jdbc.sql("UPDATE permission_grants SET revoked_at = now() WHERE membership_id = :m").param("m", atendente.membershipId()).update();
        fx.grant(atendente, "orders.delivery", "view", "ORGANIZATION", null);
        JsonNode limited = api.get("/api/v1/orders/" + oid + "/history", atendente.token()).json().path("items");
        assertThat(field(limited, "entity_type")).doesNotContain("trip", "vehicle_expense", "product");
        assertThat(field(limited, "action")).contains("orders.create");
        // Cada elo exige a capacidade do próprio recurso: "expenses" não abre abastecimento (fuel) nem recebível de
        // cliente (payments); "fuel" abre o abastecimento da viagem.
        Person gastos = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Conferente de Gastos");
        jdbc.sql("UPDATE permission_grants SET revoked_at = now() WHERE membership_id = :m").param("m", gastos.membershipId()).update();
        fx.grant(gastos, "orders.delivery", "view", "ORGANIZATION", null);
        fx.grant(gastos, "expenses", "view", "ORGANIZATION", null);
        JsonNode soGastos = api.get("/api/v1/orders/" + oid + "/history", gastos.token()).json().path("items");
        assertThat(field(soGastos, "entity_type")).doesNotContain("vehicle_expense", "receivable");
        fx.grant(gastos, "fuel", "view", "ORGANIZATION", null);
        JsonNode comFuel = api.get("/api/v1/orders/" + oid + "/history", gastos.token()).json().path("items");
        assertThat(field(comFuel, "entity_type")).contains("vehicle_expense");
    }

    // ---------- Critério: dono identifica quem alterou cada campo; campo sensível sai mascarado ----------
    @Test
    void quemAlterouCadaCampoComCampoSensivelMascarado() {
        // O dono altera o preço pelo app: antes/depois no detalhe do evento, com o client do app Patrão.
        JsonNode prod = api.get("/api/v1/products/" + product, app).json();
        Api.Res price = api.post("/api/v1/products/" + product + "/prices", app, Map.of("price", "8.10"),
                "\"" + prod.path("version").asLong() + "\"");
        assertThat(price.status()).as(price.body()).isEqualTo(200);
        JsonNode page = api.get("/api/v1/audit-events?entity_type=product&entity_id=" + product + "&limit=10", app).json();
        String eventId = null;
        for (JsonNode e : page.path("items")) {
            if (eventId == null && e.path("action").asString().equals("catalog.products.price")) { // mais recente primeiro
                eventId = e.path("id").asString();
                assertThat(e.path("client_id").asString()).isEqualTo(AppClients.GESTAO);
                assertThat(e.path("actor_name").asString()).isEqualTo("Patrão Fictício");
            }
        }
        assertThat(eventId).isNotNull();
        Api.Res detail = api.get("/api/v1/audit-events/" + eventId, app);
        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        assertThat(detail.json().path("changes").toString()).contains("8.10").contains("7.50");
        assertThat(detail.json().path("masked_fields")).isEmpty();

        // O cadastro registra QUAIS campos mudaram (sem gravar valor sensível): o dono vê quem alterou cada campo.
        JsonNode c = api.get("/api/v1/customers/" + customer, app).json();
        assertThat(api.put("/api/v1/customers/" + customer, app, Map.of("name", "Mercado Fictício", "contact", "(00) 0000-0000",
                "address", "Rua Nova, 1"), "\"" + c.path("version").asLong() + "\"").status()).isEqualTo(200);
        String upd = api.get("/api/v1/audit-events?entity_id=" + customer + "&limit=1", app).json().path("items").get(0).path("id").asString();
        JsonNode cd = api.get("/api/v1/audit-events/" + upd, app).json();
        assertThat(cd.path("event").path("actor_name").asString()).isEqualTo("Patrão Fictício");
        assertThat(cd.path("changes").toString()).contains("contact").contains("address").doesNotContain("Rua Nova");

        // Defesa em profundidade: se algum evento trouxer valor de campo sensível, ele sai "•••" para quem não pode ver.
        UUID ev = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit_events (id, organization_id, branch_id, actor_user_id, actor_kind, client_id, action, entity_type,
                                          entity_id, result, changes, origin)
                VALUES (:id, :o, :b, :u, 'USER', 'engenheiro-web', 'inventory.receipts.confirm', 'inventory_receipt', :e, 'SUCCESS',
                        CAST(:ch AS jsonb), 'teste')
                """).param("id", ev).param("o", org.id()).param("b", org.branch1()).param("u", patrao.userId())
                .param("e", receipt).param("ch", "{\"lines\": [{\"line_no\": 1, \"unit_cost\": \"3.40\"}], \"supplier\": "
                        + "{\"name\": \"Sítio\", \"personal_document\": \"11222333000181\"}}").update();
        String full = api.get("/api/v1/audit-events/" + ev, app).json().path("changes").toString();
        assertThat(full).contains("3.40").contains("11222333000181"); // administrador vê tudo
        Person supervisor = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Supervisora Sem Custo");
        fx.grant(supervisor, "audit", "view", "ORGANIZATION", null);
        Api.Res sup = api.get("/api/v1/audit-events/" + ev, supervisor.token());
        assertThat(sup.status()).as(sup.body()).isEqualTo(200);
        assertThat(sup.json().path("changes").toString()).doesNotContain("3.40").doesNotContain("11222333000181").contains("•••")
                .contains("Sítio").contains("line_no");
        assertThat(field(sup.json().path("masked_fields"), "").size()).isEqualTo(2);
        assertThat(sup.json().path("masked_fields").toString()).contains("unit_cost").contains("personal_document");
        // Sem permissão de histórico: nem a lista nem o detalhe. Outra empresa: não existe.
        assertThat(api.get("/api/v1/audit-events/" + eventId, balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/audit-events", balcao.token()).status()).isEqualTo(403);
        Org other = fx.org();
        Person outro = fx.person(other, other.branch1(), RoleTemplate.ADMIN, "Dono de Outra Empresa");
        assertThat(api.get("/api/v1/audit-events/" + eventId, outro.tokenFor(AppClients.GESTAO)).status()).isEqualTo(404);
    }

    // ---------- Atualização entre dispositivos e identidade real do dono ----------
    @Test
    void mudancaNoSiteChegaAoAppEVersaoVelhaERecusada() {
        // O app leu o cliente; o dono edita pelo site; o app vê a mudança e a versão velha é recusada (sem sobrescrever).
        JsonNode inApp = api.get("/api/v1/customers/" + customer, app).json();
        long v1 = inApp.path("version").asLong();
        Api.Res web = api.put("/api/v1/customers/" + customer, site, Map.of("name", "Mercado Fictício Centro",
                "address", "Rua Exemplo, 200"), "\"" + v1 + "\"");
        assertThat(web.status()).as(web.body()).isEqualTo(200);
        JsonNode reloaded = api.get("/api/v1/customers/" + customer, app).json();
        assertThat(reloaded.path("name").asString()).isEqualTo("Mercado Fictício Centro");
        Api.Res stale = api.put("/api/v1/customers/" + customer, app, Map.of("name", "Nome do app antigo"), "\"" + v1 + "\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(api.get("/api/v1/customers/" + customer, app).json().path("name").asString()).isEqualTo("Mercado Fictício Centro");
        // Cada ação ficou com o mesmo ator e o app/site de onde partiu.
        List<String> clients = new ArrayList<>();
        for (JsonNode e : api.get("/api/v1/audit-events?entity_id=" + customer + "&limit=20", app).json().path("items")) {
            assertThat(e.path("actor_user_id").asString()).isEqualTo(patrao.userId().toString());
            clients.add(e.path("client_id").asString());
        }
        assertThat(clients).contains(AppClients.GESTAO, AppClients.WEB);
    }

    // ---------- Critério: evento não autorizado não chega ao app de outro usuário ----------
    @Test
    void eventoNaoAutorizadoNaoChegaAOutroUsuario() {
        // Uma despesa pendente e uma ação do dono: o balcão não recebe nada disso pela central nem pelo histórico.
        api.postWith("/api/v1/expenses", app, Map.of("category", "ESTRUTURA", "subcategory", "Aluguel", "description",
                "Aluguel do galpão (fictício)", "amount", "1500.00"), key());
        JsonNode mine = api.get("/api/v1/approvals", balcao.token()).json();
        for (JsonNode i : mine.path("items")) {
            assertThat(i.path("kind").asString()).isNotIn("EXPENSE", "TAX", "PAYROLL", "LOSS", "VEHICLE_EXPENSE", "CASH_SESSION");
        }
        assertThat(mine.toString()).doesNotContain("1500.00").doesNotContain("Aluguel");
        assertThat(api.get("/api/v1/audit-events?limit=50", balcao.token()).status()).isEqualTo(403);
        // O motorista não lê a trilha de um pedido que não é dele.
        Map<String, Object> delivery = new HashMap<>(Map.of("address", "Rua Exemplo, 100", "agreed_payment", "CASH"));
        String oid = api.postWith("/api/v1/orders", coord.token(), Map.of("type", "DELIVERY", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", "1")), "delivery", delivery), key()).json().path("id").asString();
        assertThat(api.get("/api/v1/orders/" + oid + "/history", motorista.token()).status()).isIn(403, 404);
        // O dono vê a pendência na central com o valor.
        JsonNode owner = api.get("/api/v1/approvals", app).json();
        assertThat(owner.toString()).contains("1500.00");
    }
}
