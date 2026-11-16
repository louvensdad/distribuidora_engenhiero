package br.com.engenheiro.api.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** F05: pedido de entrega, frota, viagem, entrega parcial, ocorrência, retorno, prestação de contas, gastos e rateio. */
@IntegrationTest
class LogisticsIntegrationTest {
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
    Person balcao;
    String product;
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        coord = fx.person(org, org.branch1(), RoleTemplate.COORDENADOR_ENTREGAS, "Coordenação");
        motoristaA = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Carlos Motorista");
        motoristaB = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Roberto Motorista");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
        for (String[] g : List.of(new String[] {"trips", "assign"}, new String[] {"trips", "close"},
                new String[] {"fuel", "approve"}, new String[] {"fuel", "view"}, new String[] {"fuel", "create"}, new String[] {"expenses", "approve"},
                new String[] {"expenses", "view"}, new String[] {"vehicles", "create"},
                new String[] {"vehicles", "edit_draft"})) {
            fx.grant(coord, g[0], g[1], g[0].equals("vehicles") ? "BRANCH" : "SECTOR", null);
        }
        for (Person m : List.of(motoristaA, motoristaB)) {
            fx.grant(m, "payments", "confirm", "BRANCH", null);
            fx.grant(m, "payments", "create", "BRANCH", null);
            fx.grant(m, "own_cash", "view", "OWN", null);
            fx.grant(m, "own_cash", "create", "OWN", null);
            fx.grant(m, "own_cash", "close", "OWN", null);
            fx.grant(m, "trips", "close", "ASSIGNED", null);
        }
        // Cana em dúzias a R$ 7,50; 200 dúzias a custo 3,00; feixe = 3 dúzias.
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA", "name", "Cana caiana",
                "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        product = p.json().path("id").asString();
        assertThat(api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "7.50"),
                p.header("ETag")).status()).isEqualTo(200);
        String etag = api.get("/api/v1/products/" + product, patrao.token()).header("ETag");
        Api.Res conv = api.post("/api/v1/products/" + product + "/conversions", patrao.token(),
                Map.of("unit", "FEIXE", "factor", "3"), etag);
        assertThat(conv.status()).as(conv.body()).isEqualTo(200);
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio"), (String) null).json()
                .path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "200", "unit_cost", "3.00"))), key()).json();
        api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        customer = api.post("/api/v1/customers", coord.token(), Map.of("name", "Mercado Central",
                "address", "Rua Exemplo, 100"), (String) null).json().path("id").asString();
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 'I', 'E', 'N', 'D',
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

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
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("address", "Rua Exemplo, 100 — Centro");
        delivery.put("reference", "Portão azul");
        delivery.put("contact", "(11) 90000-0000");
        delivery.put("window_start", "2026-11-17T08:00:00-03:00");
        delivery.put("window_end", "2026-11-17T12:00:00-03:00");
        delivery.put("agreed_payment", agreed);
        Api.Res o = api.postWith("/api/v1/orders", coord.token(), Map.of("type", "DELIVERY", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", qty)), "delivery", delivery), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        return o.json().path("id").asString();
    }

    JsonNode vehicle(String plate, String capacity, String unit) {
        Api.Res v = api.postWith("/api/v1/vehicles", coord.token(), Map.of("plate", plate, "model", "Fiorino",
                "capacity_quantity", capacity, "capacity_unit", unit, "odometer_km", "45200"), key());
        assertThat(v.status()).as(v.body()).isEqualTo(201);
        return v.json();
    }

    JsonNode trip() {
        Api.Res t = api.postWith("/api/v1/trips", coord.token(), Map.of(), key());
        assertThat(t.status()).as(t.body()).isEqualTo(201);
        return t.json();
    }

    Api.Res assign(JsonNode trip, String vehicleId, Person driver, String... orders) {
        return api.postWith("/api/v1/trips/" + trip.path("id").asString() + "/assignments", coord.token(),
                Map.of("vehicle_id", vehicleId, "driver_id", driver.userId().toString(), "order_ids", List.of(orders)),
                ifMatch(trip.path("version").asLong()));
    }

    JsonNode assigned(String vehicleId, Person driver, String... orders) {
        Api.Res a = assign(trip(), vehicleId, driver, orders);
        assertThat(a.status()).as(a.body()).isEqualTo(200);
        return a.json();
    }

    JsonNode depart(Person who, JsonNode t, String odo) {
        Api.Res d = api.postWith("/api/v1/trips/" + t.path("id").asString() + "/depart", who.token(),
                Map.of("odometer", odo), ifMatch(t.path("version").asLong()));
        assertThat(d.status()).as(d.body()).isEqualTo(200);
        return d.json();
    }

    BigDecimal balance(String account) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND l.account = :a
                        """).param("o", org.id()).param("a", account).query(BigDecimal.class).single();
    }

    static String stopOf(JsonNode trip, String orderId) {
        for (JsonNode s : trip.path("stops")) {
            if (s.path("order").path("id").asString().equals(orderId)) {
                return s.path("id").asString();
            }
        }
        throw new AssertionError("parada não encontrada");
    }

    // ---------- LOG-01: pedido de entrega ----------
    @Test
    void pedidoDeEntregaExigeEnderecoEPagamentoCombinadoEMascaraParaQuemNaoVe() {
        Api.Res sem = api.postWith("/api/v1/orders", coord.token(), Map.of("type", "DELIVERY", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", "1"))), key());
        assertThat(sem.status()).isEqualTo(422);
        assertThat(sem.body()).contains("delivery");
        Api.Res janela = api.postWith("/api/v1/orders", coord.token(), Map.of("type", "DELIVERY", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", "1")), "delivery", Map.of("address", "Rua 1",
                        "agreed_payment", "PIX", "window_start", "2026-11-17T12:00:00Z", "window_end", "2026-11-17T08:00:00Z")),
                key());
        assertThat(janela.status()).isEqualTo(422);
        assertThat(janela.body()).contains("delivery.window_end");

        String id = deliveryOrder("2", "PIX");
        JsonNode o = api.get("/api/v1/orders/" + id, coord.token()).json();
        assertThat(o.path("delivery").path("address").asString()).isEqualTo("Rua Exemplo, 100 — Centro");
        assertThat(o.path("delivery").path("agreed_payment").asString()).isEqualTo("PIX");
        assertThat(o.path("sector").isMissingNode()).isTrue();

        // Quem vê pedidos de entrega sem a política de endereço recebe o campo oculto, sem valor.
        fx.grant(balcao, "orders.delivery", "view", "BRANCH", null);
        JsonNode b = api.get("/api/v1/orders/" + id, balcao.token()).json();
        assertThat(b.path("delivery").path("address").isNull()).isTrue();
        assertThat(b.path("masked_fields").toString()).contains("delivery.address");
        assertThat(b.path("delivery").path("contact").asString()).isEqualTo("(11) 90000-0000"); // contato liberado
    }

    // ---------- Critério: motorista consulta somente viagens atribuídas ----------
    @Test
    void motoristaConsultaSomenteViagensAtribuidas() {
        JsonNode v1 = vehicle("ABC1D23", "100", "DUZIA");
        JsonNode v2 = vehicle("DEF4G56", "100", "DUZIA");
        String o1 = deliveryOrder("4", "CASH");
        String o2 = deliveryOrder("4", "CASH");
        JsonNode tA = assigned(v1.path("id").asString(), motoristaA, o1);
        JsonNode tB = assigned(v2.path("id").asString(), motoristaB, o2);

        JsonNode listA = api.get("/api/v1/trips", motoristaA.token()).json().path("items");
        assertThat(listA).hasSize(1);
        assertThat(listA.get(0).path("id").asString()).isEqualTo(tA.path("id").asString());
        // Viagem de outro motorista: 404 (não revela existência) e tentativa auditada.
        assertThat(api.get("/api/v1/trips/" + tB.path("id").asString(), motoristaA.token()).status()).isEqualTo(404);
        assertThat(api.postWith("/api/v1/trips/" + tB.path("id").asString() + "/depart", motoristaA.token(),
                Map.of("odometer", "45200"), ifMatch(tB.path("version").asLong())).status()).isEqualTo(404);
        // Vê endereço e contato da entrega atribuída, sem acesso ao cadastro/pedidos.
        JsonNode mine = api.get("/api/v1/trips/" + tA.path("id").asString(), motoristaA.token()).json();
        JsonNode stopOrder = mine.path("stops").get(0).path("order");
        assertThat(stopOrder.path("delivery").path("address").asString()).isEqualTo("Rua Exemplo, 100 — Centro");
        assertThat(stopOrder.path("balance_due").asString()).isEqualTo("30.00");
        assertThat(api.get("/api/v1/orders/" + o1, motoristaA.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/customers/" + customer, motoristaA.token()).status()).isEqualTo(403);
        // Veículos: só o da própria viagem.
        JsonNode vs = api.get("/api/v1/vehicles", motoristaA.token()).json().path("items");
        assertThat(vs).hasSize(1);
        assertThat(vs.get(0).path("plate").asString()).isEqualTo("ABC1D23");
        assertThat(api.get("/api/v1/vehicles/" + v2.path("id").asString(), motoristaA.token()).status()).isEqualTo(404);
        int denied = jdbc.sql("SELECT count(*) FROM audit_events WHERE actor_user_id = :u AND action = 'access.denied'")
                .param("u", motoristaA.userId()).query(Integer.class).single();
        assertThat(denied).isGreaterThanOrEqualTo(3);
        // Motorista não cria nem atribui viagens.
        assertThat(api.postWith("/api/v1/trips", motoristaA.token(), Map.of(), key()).status()).isEqualTo(403);
    }

    // ---------- Critério: veículo incapaz ou indisponível não recebe atribuição inválida ----------
    @Test
    void veiculoIncapazOuIndisponivelNaoRecebeAtribuicao() {
        JsonNode pequeno = vehicle("AAA1A11", "10", "DUZIA");
        String grande = deliveryOrder("12", "CASH");
        Api.Res cap = assign(trip(), pequeno.path("id").asString(), motoristaA, grande);
        assertThat(cap.status()).isEqualTo(422);
        assertThat(cap.body()).contains("acima da capacidade");

        // Capacidade em feixes: 12 dúzias = 4 feixes cabem em 5 feixes.
        JsonNode feixes = vehicle("BBB2B22", "5", "FEIXE");
        assertThat(assign(trip(), feixes.path("id").asString(), motoristaA, grande).status()).isEqualTo(200);
        // Medida sem conversão para o produto: capacidade não pode ser validada → recusa.
        JsonNode caixas = vehicle("CCC3C33", "100", "CAIXA");
        String outro = deliveryOrder("1", "CASH");
        Api.Res semConv = assign(trip(), caixas.path("id").asString(), motoristaB, outro);
        assertThat(semConv.status()).isEqualTo(422);
        assertThat(semConv.body()).contains("Sem conversão");

        // Em manutenção.
        JsonNode oficina = vehicle("DDD4D44", "100", "DUZIA");
        assertThat(api.post("/api/v1/vehicles/" + oficina.path("id").asString() + "/status", coord.token(),
                Map.of("status", "MAINTENANCE", "reason", "Freio"), "\"1\"").status()).isEqualTo(200);
        Api.Res manut = assign(trip(), oficina.path("id").asString(), motoristaB, outro);
        assertThat(manut.status()).isEqualTo(422);
        assertThat(manut.body()).contains("em manutenção");
        // Seguro vencido.
        JsonNode vencido = vehicle("EEE5E55", "100", "DUZIA");
        jdbc.sql("UPDATE vehicles SET insurance_valid_until = :d WHERE id = :id").param("d", LocalDate.now().minusDays(1))
                .param("id", UUID.fromString(vencido.path("id").asString())).update();
        Api.Res seguro = assign(trip(), vencido.path("id").asString(), motoristaB, outro);
        assertThat(seguro.status()).isEqualTo(422);
        assertThat(seguro.body()).contains("Seguro do veículo vencido");
        // Veículo já em outra viagem; motorista já em outra viagem.
        JsonNode ok = vehicle("FFF6F66", "100", "DUZIA");
        Api.Res ocupado = assign(trip(), feixes.path("id").asString(), motoristaB, outro);
        assertThat(ocupado.status()).isEqualTo(422);
        assertThat(ocupado.body()).contains("já está na viagem");
        Api.Res motoristaOcupado = assign(trip(), ok.path("id").asString(), motoristaA, outro);
        assertThat(motoristaOcupado.status()).isEqualTo(422);
        assertThat(motoristaOcupado.body()).contains("Motorista já está na viagem");
        // Pessoa sem autorização de motorista; pedido de retirada; pedido já em outra viagem.
        assertThat(assign(trip(), ok.path("id").asString(), balcao, outro).body()).contains("não autorizada como motorista");
        Api.Res repetido = assign(trip(), ok.path("id").asString(), motoristaB, grande);
        assertThat(repetido.status()).isEqualTo(422);
        assertThat(repetido.body()).contains("já está na viagem");
        // Nada ficou atribuído pelas tentativas inválidas.
        int stops = jdbc.sql("SELECT count(*) FROM trip_stops s JOIN trips t ON t.id = s.trip_id WHERE t.organization_id = :o AND s.is_open")
                .param("o", org.id()).query(Integer.class).single();
        assertThat(stops).isEqualTo(1);
    }

    @Test
    void mesmoPedidoEmDuasViagensSimultaneasSoUmaGanha() throws Exception {
        JsonNode v1 = vehicle("GGG7G77", "100", "DUZIA");
        JsonNode v2 = vehicle("HHH8H88", "100", "DUZIA");
        String o = deliveryOrder("2", "CASH");
        JsonNode t1 = trip();
        JsonNode t2 = trip();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> fs = new ArrayList<>();
        Object[][] calls = {{t1, v1, motoristaA}, {t2, v2, motoristaB}};
        for (Object[] c : calls) {
            fs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return assign((JsonNode) c[0], ((JsonNode) c[1]).path("id").asString(), (Person) c[2], o).status();
            }));
        }
        go.countDown();
        List<Integer> st = fs.stream().map(CompletableFuture::join).toList();
        assertThat(st).containsExactlyInAnyOrder(200, 422);
        int open = jdbc.sql("SELECT count(*) FROM trip_stops WHERE order_id = :o AND is_open")
                .param("o", UUID.fromString(o)).query(Integer.class).single();
        assertThat(open).isEqualTo(1);
    }

    // ---------- Critério: odômetro regressivo rejeitado ----------
    @Test
    void odometroRegressivoRejeitado() {
        JsonNode v = vehicle("JJJ9J99", "100", "DUZIA");
        String vid = v.path("id").asString();
        String o = deliveryOrder("2", "CASH");
        JsonNode t = assigned(vid, motoristaA, o);
        Api.Res back = api.postWith("/api/v1/trips/" + t.path("id").asString() + "/depart", motoristaA.token(),
                Map.of("odometer", "45100"), ifMatch(t.path("version").asLong()));
        assertThat(back.status()).isEqualTo(422);
        assertThat(back.body()).contains("Odômetro regressivo");
        JsonNode d = depart(motoristaA, t, "45250.5");
        assertThat(d.path("state").asString()).isEqualTo("DEPARTED");
        // Abastecimento com odômetro menor que a saída.
        Api.Res fuel = api.postWith("/api/v1/vehicles/" + vid + "/fuel", motoristaA.token(), Map.of("odometer", "45000",
                "liters", "30", "fuel_type", "DIESEL", "full_tank", true, "amount", "180.00"), key());
        assertThat(fuel.status()).isEqualTo(422);
        assertThat(fuel.body()).contains("odometer");
        // O banco também recusa leitura regressiva (gatilho), mesmo fora da API.
        Throwable direct = null;
        try {
            jdbc.sql("INSERT INTO odometer_readings (id, vehicle_id, reading, kind, recorded_by) VALUES (gen_random_uuid(), :v, 100, 'FUEL', :u)")
                    .param("v", UUID.fromString(vid)).param("u", patrao.userId()).update();
        } catch (RuntimeException e) {
            direct = e;
        }
        assertThat(direct).isNotNull();
        assertThat(direct.getMessage()).contains("odômetro regressivo");
        // Correção só pelo procedimento aprovado: administrador com motivo.
        assertThat(api.post("/api/v1/vehicles/" + vid + "/odometer-corrections", coord.token(),
                Map.of("reading", "45000", "reason", "erro de digitação"), (String) null).status()).isEqualTo(403);
        Api.Res fix = api.post("/api/v1/vehicles/" + vid + "/odometer-corrections", patrao.token(),
                Map.of("reading", "45240", "reason", "erro de digitação na saída"), (String) null);
        assertThat(fix.status()).as(fix.body()).isEqualTo(200);
        assertThat(fix.json().path("odometer_km").asString()).isEqualTo("45240.0");
        List<String> kinds = jdbc.sql("SELECT kind FROM odometer_readings WHERE vehicle_id = :v ORDER BY recorded_at")
                .param("v", UUID.fromString(vid)).query(String.class).list();
        assertThat(kinds).containsExactly("INITIAL", "DEPARTURE", "CORRECTION");
    }

    // ---------- Fluxo: cadastro → despacho → motorista → entrega → ocorrência → retorno → conferência ----------
    @Test
    void fluxoCompletoComEntregaParcialOcorrenciaRetornoEPrestacaoDeContas() {
        JsonNode v = vehicle("KKK1K11", "100", "DUZIA");
        String vid = v.path("id").asString();
        String o1 = deliveryOrder("10", "CASH");      // R$ 75,00 em dinheiro na entrega
        String o2 = deliveryOrder("20", "ON_ACCOUNT"); // a prazo autorizado pelo patrão
        String o3 = deliveryOrder("4", "PIX");        // cliente ausente
        Api.Res prazo = api.postWith("/api/v1/orders/" + o2 + "/payments", patrao.token(), Map.of("parts", List.of(
                Map.of("method", "ON_ACCOUNT", "amount", "150.00", "due_date", "2026-12-15"))), key());
        assertThat(prazo.status()).as(prazo.body()).isEqualTo(200);
        JsonNode t = assigned(vid, motoristaA, o1, o2, o3);
        assertThat(t.path("state").asString()).isEqualTo("ASSIGNED");
        assertThat(t.path("load").asString()).isEqualTo("34");
        assertThat(t.path("driver_name").asString()).isEqualTo("Carlos Motorista");
        String tid = t.path("id").asString();
        // Pedido mostra a viagem e o motorista (W04).
        JsonNode od = api.get("/api/v1/orders/" + o1, coord.token()).json().path("dispatch");
        assertThat(od.path("plate").asString()).isEqualTo("KKK1K11");
        assertThat(od.path("trip_state").asString()).isEqualTo("ASSIGNED");

        // Entregar antes de sair: recusado.
        Api.Res cedo = api.postWith("/api/v1/trips/" + tid + "/deliveries", motoristaA.token(), Map.of("stop_id",
                stopOf(t, o1), "recipient_name", "Ana"), key());
        assertThat(cedo.status()).isEqualTo(409);
        t = depart(motoristaA, t, "45210");

        // Parada 1: dinheiro na entrega, com caixa do motorista aberto.
        Api.Res cx = api.postWith("/api/v1/cash-sessions", motoristaA.token(), Map.of("opening_float", "0.00"), key());
        assertThat(cx.status()).as(cx.body()).isEqualTo(201);
        Map<String, String> k1 = key();
        Map<String, Object> deliver1 = Map.of("stop_id", stopOf(t, o1), "recipient_name", "Ana (gerente)",
                "payment", Map.of("parts", List.of(Map.of("method", "CASH", "amount", "75.00", "presented_cash", "100.00"))));
        Api.Res d1 = api.postWith("/api/v1/trips/" + tid + "/deliveries", motoristaA.token(), deliver1, k1);
        assertThat(d1.status()).as(d1.body()).isEqualTo(200);
        // Repetição com a mesma chave: sem segunda baixa nem segundo recebimento.
        assertThat(api.postWith("/api/v1/trips/" + tid + "/deliveries", motoristaA.token(), deliver1, k1).status())
                .isEqualTo(200);
        int pays = jdbc.sql("SELECT count(*) FROM payments WHERE order_id = :o").param("o", UUID.fromString(o1))
                .query(Integer.class).single();
        assertThat(pays).isEqualTo(1);
        JsonNode p1 = api.get("/api/v1/orders/" + o1, coord.token()).json();
        assertThat(p1.path("state").asString()).isEqualTo("FULFILLED");
        assertThat(p1.path("payment_state").asString()).isEqualTo("PAID");

        // Evidência da entrega (LOG-03): foto anexada à parada, visível a quem vê a viagem; outro motorista não alcança.
        String stop1 = stopOf(t, o1);
        Api.Res foto = api.upload("/api/v1/trips/" + tid + "/stops/" + stop1 + "/attachments", motoristaA.token(),
                "canhoto.png", "image/png", PNG);
        assertThat(foto.status()).as(foto.body()).isEqualTo(201);
        assertThat(foto.json().path("state").asString()).isEqualTo("AVAILABLE");
        assertThat(api.get("/api/v1/trips/" + tid + "/stops/" + stop1 + "/attachments", motoristaB.token()).status())
                .isEqualTo(404);
        Api.Res baixa = api.getBytes("/api/v1/documents/" + foto.json().path("id").asString() + "/download", coord.token());
        assertThat(baixa.status()).isEqualTo(200);
        assertThat(api.getBytes("/api/v1/documents/" + foto.json().path("id").asString() + "/download",
                motoristaB.token()).status()).isEqualTo(404);

        // Parada 2: entrega parcial exige motivo e gera ocorrência.
        Map<String, Object> parcialSemMotivo = Map.of("stop_id", stopOf(t, o2), "recipient_name", "João",
                "lines", List.of(Map.of("line_no", 1, "quantity", "12")));
        Api.Res semMotivo = api.postWith("/api/v1/trips/" + tid + "/deliveries", motoristaA.token(), parcialSemMotivo, key());
        assertThat(semMotivo.status()).isEqualTo(422);
        int baixas = jdbc.sql("SELECT count(*) FROM fulfillments WHERE order_id = :o").param("o", UUID.fromString(o2))
                .query(Integer.class).single();
        assertThat(baixas).isZero(); // tudo desfeito na recusa
        Map<String, Object> parcial = new HashMap<>(parcialSemMotivo);
        parcial.put("partial_reason", "Cliente só tinha espaço para 12 dúzias");
        Api.Res d2 = api.postWith("/api/v1/trips/" + tid + "/deliveries", motoristaA.token(), parcial, key());
        assertThat(d2.status()).as(d2.body()).isEqualTo(200);

        // Parada 3: cliente ausente → ocorrência, não entregue.
        Api.Res inc = api.postWith("/api/v1/trips/" + tid + "/incidents", motoristaA.token(), Map.of("stop_id",
                stopOf(t, o3), "kind", "CUSTOMER_ABSENT", "description", "Portão fechado, sem resposta",
                "requested_action", "RESCHEDULE"), key());
        assertThat(inc.status()).as(inc.body()).isEqualTo(200);
        JsonNode afterInc = inc.json();
        Map<String, String> states = new HashMap<>();
        for (JsonNode s : afterInc.path("stops")) {
            states.put(s.path("order").path("id").asString(), s.path("state").asString());
        }
        assertThat(states).containsEntry(o1, "DELIVERED").containsEntry(o2, "PARTIAL").containsEntry(o3, "FAILED");
        // Ocorrência não entrega: o pedido segue reservado e sem venda.
        JsonNode p3 = api.get("/api/v1/orders/" + o3, coord.token()).json();
        assertThat(p3.path("state").asString()).isEqualTo("CONFIRMED");
        // Pedido em rota não é cancelado pelo escritório.
        Api.Res cancelEmRota = api.postWith("/api/v1/orders/" + o3 + "/cancellations", patrao.token(),
                Map.of("reason", "teste"), key(p3.path("version").asLong()));
        assertThat(cancelEmRota.status()).isEqualTo(409);
        assertThat(cancelEmRota.body()).contains("em rota");

        // Abastecimento pago com o dinheiro do motorista durante a viagem.
        Api.Res fuel = api.postWith("/api/v1/vehicles/" + vid + "/fuel", motoristaA.token(), Map.of("odometer", "45260",
                "liters", "3.5", "fuel_type", "DIESEL", "full_tank", false, "amount", "20.00", "station", "Posto Exemplo",
                "paid_with", "DRIVER_CASH"), key());
        assertThat(fuel.status()).as(fuel.body()).isEqualTo(201);
        assertThat(fuel.json().path("trip_number").asLong()).isEqualTo(t.path("number").asLong());

        // Retorno: dinheiro a prestar = 75,00 recebidos − 20,00 do abastecimento = 55,00.
        long ver = api.get("/api/v1/trips/" + tid, motoristaA.token()).json().path("version").asLong();
        Api.Res faltando = api.postWith("/api/v1/trips/" + tid + "/return", motoristaA.token(), Map.of("odometer", "45290",
                "cash_declared", "50.00"), key(ver));
        assertThat(faltando.status()).isEqualTo(422);
        assertThat(faltando.body()).contains("justificativa");
        Api.Res odoBack = api.postWith("/api/v1/trips/" + tid + "/return", motoristaA.token(), Map.of("odometer", "45200",
                "cash_declared", "55.00"), key(ver));
        assertThat(odoBack.status()).isEqualTo(422);
        Api.Res ret = api.postWith("/api/v1/trips/" + tid + "/return", motoristaA.token(), Map.of("odometer", "45290",
                "cash_declared", "55.00"), key(ver));
        assertThat(ret.status()).as(ret.body()).isEqualTo(200);
        JsonNode r = ret.json();
        assertThat(r.path("state").asString()).isEqualTo("RETURNED");
        assertThat(r.path("cash_collected").asString()).isEqualTo("75.00");
        assertThat(r.path("cash_expenses").asString()).isEqualTo("20.00");
        assertThat(r.path("cash_expected").asString()).isEqualTo("55.00");
        assertThat(r.path("cash_difference").asString()).isEqualTo("0.00");
        assertThat(r.path("distance_km").asString()).isEqualTo("80.0");

        // Pedidos não entregues voltam para despacho; o parcial também (saldo de 8 dúzias).
        int open = jdbc.sql("SELECT count(*) FROM trip_stops WHERE trip_id = :t AND is_open")
                .param("t", UUID.fromString(tid)).query(Integer.class).single();
        assertThat(open).isZero();
        JsonNode v2 = vehicle("LLL2L22", "100", "DUZIA");
        JsonNode nova = assigned(v2.path("id").asString(), motoristaB, o2, o3);
        assertThat(nova.path("load").asString()).isEqualTo("12"); // 8 restantes + 4

        // Conferência: não pelo motorista; gasto pendente bloqueia; depois de aprovado, concilia.
        long rv = r.path("version").asLong();
        assertThat(api.post("/api/v1/trips/" + tid + "/reconcile", motoristaA.token(), Map.of(), "\"" + rv + "\"")
                .status()).isEqualTo(403);
        Api.Res pend = api.post("/api/v1/trips/" + tid + "/reconcile", coord.token(), Map.of(), "\"" + rv + "\"");
        assertThat(pend.status()).isEqualTo(409);
        assertThat(pend.body()).contains("aguardando aprovação");
        String fid = fuel.json().path("id").asString();
        assertThat(api.postWith("/api/v1/vehicle-expenses/" + fid + "/approve", motoristaA.token(), Map.of(), key(1))
                .status()).isEqualTo(403); // não aprova o próprio gasto (nem tem fuel.approve)
        Api.Res apr = api.postWith("/api/v1/vehicle-expenses/" + fid + "/approve", coord.token(), Map.of(), key(1));
        assertThat(apr.status()).as(apr.body()).isEqualTo(200);
        Api.Res rec = api.post("/api/v1/trips/" + tid + "/reconcile", coord.token(), Map.of(), "\"" + rv + "\"");
        assertThat(rec.status()).as(rec.body()).isEqualTo(200);
        assertThat(rec.json().path("state").asString()).isEqualTo("RECONCILED");
        assertThat(rec.json().path("reconciled_by_name").asString()).isEqualTo("Coordenação");

        // Livro: venda só do entregue (75 + 12×7,50 = 165), CMV pelo custo; caixa = 75 − 20; despesa uma vez.
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("-165.00");
        assertThat(balance("CMV")).isEqualByComparingTo("66.00");
        assertThat(balance("CAIXA")).isEqualByComparingTo("55.00");
        assertThat(balance("DESPESAS_VEICULOS")).isEqualByComparingTo("20.00");
        assertThat(balance("ADIANTAMENTOS_FUNCIONARIOS")).isEqualByComparingTo("0.00");
        // Caixa do motorista: esperado 0 + 75 − 20.
        JsonNode sess = api.get("/api/v1/cash-sessions/current", motoristaA.token()).json().path("session");
        assertThat(sess.path("expected").asString()).isEqualTo("55.00");

        // Histórico do pedido mostra a atribuição, a ocorrência e a entrega, com autor.
        List<String> acts = new ArrayList<>();
        for (JsonNode h : api.get("/api/v1/orders/" + o3 + "/history", coord.token()).json().path("items")) {
            acts.add(h.path("action").asString() + "/" + h.path("actor_name").asString());
        }
        assertThat(acts).contains("trips.order_assigned/Coordenação", "trips.incident/Carlos Motorista");
        // Histórico do veículo: viagem e gasto.
        List<String> vh = new ArrayList<>();
        for (JsonNode h : api.get("/api/v1/vehicles/" + vid + "/history", coord.token()).json().path("items")) {
            vh.add(h.path("action").asString());
        }
        assertThat(vh).contains("vehicles.create", "trips.depart", "trips.return", "vehicles.fuel",
                "vehicles.expense.approve");
    }

    // ---------- Critério: gasto rateado soma ao original (sem duplicar a despesa real) ----------
    @Test
    void gastoRateadoSomaAoOriginalSemDuplicarDespesa() {
        JsonNode v = vehicle("MMM3M33", "100", "DUZIA");
        String vid = v.path("id").asString();
        String a = deliveryOrder("1", "ON_ACCOUNT");
        String b = deliveryOrder("1", "ON_ACCOUNT");
        String c = deliveryOrder("1", "ON_ACCOUNT");
        for (String o : List.of(a, b, c)) {
            api.postWith("/api/v1/orders/" + o + "/payments", patrao.token(), Map.of("parts", List.of(
                    Map.of("method", "ON_ACCOUNT", "amount", "7.50", "due_date", "2026-12-15"))), key());
        }
        JsonNode t = assigned(vid, motoristaA, a, b, c);
        String tid = t.path("id").asString();
        t = depart(motoristaA, t, "45300");
        // Gasto antes de qualquer entrega: não alocado, sem dividir por zero.
        Api.Res pedagio = api.postWith("/api/v1/vehicles/" + vid + "/maintenance", coord.token(), Map.of("kind", "TOLL",
                "amount", "100.00", "description", "Pedágio ida e volta", "trip_id", tid), key());
        assertThat(pedagio.status()).as(pedagio.body()).isEqualTo(201);
        assertThat(api.postWith("/api/v1/vehicle-expenses/" + pedagio.json().path("id").asString() + "/approve",
                coord.token(), Map.of(), key(1)).status()).isEqualTo(403); // quem registrou não aprova
        Api.Res apr = api.postWith("/api/v1/vehicle-expenses/" + pedagio.json().path("id").asString() + "/approve",
                patrao.token(), Map.of("due_date", "2026-12-01"), key(1));
        assertThat(apr.status()).as(apr.body()).isEqualTo(200);
        JsonNode zero = api.get("/api/v1/trips/" + tid + "/costs", coord.token()).json();
        assertThat(zero.path("unallocated").asString()).isEqualTo("100.00");
        assertThat(zero.path("message").asString()).contains("não alocado");

        for (String o : List.of(a, b, c)) {
            Api.Res d = api.postWith("/api/v1/trips/" + tid + "/deliveries", motoristaA.token(), Map.of("stop_id",
                    stopOf(t, o), "recipient_name", "Recebedor"), key());
            assertThat(d.status()).as(d.body()).isEqualTo(200);
        }
        JsonNode costs = api.get("/api/v1/trips/" + tid + "/costs", coord.token()).json();
        assertThat(costs.path("rule_code").asString()).isEqualTo("TRIP_COST");
        assertThat(costs.path("rule_version").asInt()).isEqualTo(1);
        assertThat(costs.path("total_cost").asString()).isEqualTo("100.00");
        List<String> shares = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode l : costs.path("lines")) {
            shares.add(l.path("allocated_cost").asString());
            sum = sum.add(new BigDecimal(l.path("allocated_cost").asString()));
        }
        assertThat(shares).containsExactly("33.33", "33.33", "33.34"); // diferença na última parada
        assertThat(sum).isEqualByComparingTo("100.00");
        assertThat(costs.path("allocated_total").asString()).isEqualTo("100.00");
        assertThat(costs.path("unallocated").asString()).isEqualTo("0.00");
        // Consultar o rateio de novo não cria lançamento: a despesa real existe uma vez.
        api.get("/api/v1/trips/" + tid + "/costs", coord.token());
        int entries = jdbc.sql("""
                        SELECT count(*) FROM journal_entries WHERE organization_id = :o AND event_kind = 'EXPENSE_APPROVED'
                        """).param("o", org.id()).query(Integer.class).single();
        assertThat(entries).isEqualTo(1);
        assertThat(balance("DESPESAS_VEICULOS")).isEqualByComparingTo("100.00");
        int payables = jdbc.sql("SELECT count(*) FROM payables WHERE source_type = 'vehicle_expense' AND organization_id = :o")
                .param("o", org.id()).query(Integer.class).single();
        assertThat(payables).isEqualTo(1);
        // Motorista não vê custo da viagem.
        assertThat(api.get("/api/v1/trips/" + tid + "/costs", motoristaA.token()).status()).isEqualTo(403);
    }

    // ---------- LOG-05: consumo só com dados suficientes ----------
    @Test
    void consumoKmPorLitroSoEntreTanquesCheios() {
        JsonNode v = vehicle("NNN4N44", "100", "DUZIA");
        String vid = v.path("id").asString();
        assertThat(api.postWith("/api/v1/vehicles/" + vid + "/fuel", coord.token(), Map.of("odometer", "45300",
                "liters", "40", "fuel_type", "DIESEL", "full_tank", true, "amount", "240.00"), key()).status()).isEqualTo(201);
        JsonNode um = api.get("/api/v1/vehicles/" + vid, coord.token()).json().path("consumption");
        assertThat(um.path("state").asString()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(um.path("km_per_liter").isNull()).isTrue();
        api.postWith("/api/v1/vehicles/" + vid + "/fuel", coord.token(), Map.of("odometer", "45500", "liters", "10",
                "fuel_type", "DIESEL", "full_tank", false, "amount", "60.00"), key());
        assertThat(api.get("/api/v1/vehicles/" + vid, coord.token()).json().path("consumption").path("state").asString())
                .isEqualTo("INSUFFICIENT_DATA"); // parcial não fecha a conta
        api.postWith("/api/v1/vehicles/" + vid + "/fuel", coord.token(), Map.of("odometer", "45700", "liters", "30",
                "fuel_type", "DIESEL", "full_tank", true, "amount", "180.00"), key());
        JsonNode c = api.get("/api/v1/vehicles/" + vid, coord.token()).json().path("consumption");
        assertThat(c.path("state").asString()).isEqualTo("OK");
        assertThat(c.path("km").asString()).isEqualTo("400.0");
        assertThat(c.path("liters").asString()).isEqualTo("40");
        assertThat(c.path("km_per_liter").asString()).isEqualTo("10.00");
        // Manutenção: revisão prevista vira aviso (não bloqueia).
        Api.Res m = api.postWith("/api/v1/vehicles/" + vid + "/maintenance", coord.token(), Map.of("kind", "MAINTENANCE",
                "maintenance_type", "PREVENTIVE", "odometer", "45700", "amount", "350.00", "workshop", "Oficina Exemplo",
                "next_service_km", "45700"), key());
        assertThat(m.status()).as(m.body()).isEqualTo(201);
        JsonNode alerts = api.get("/api/v1/vehicles/" + vid, coord.token()).json().path("alerts");
        assertThat(alerts.toString()).contains("SERVICE_DUE");
        // Cadastro ≠ pagamento: nada no livro até aprovar.
        assertThat(balance("DESPESAS_VEICULOS")).isEqualByComparingTo("0");
    }
}
