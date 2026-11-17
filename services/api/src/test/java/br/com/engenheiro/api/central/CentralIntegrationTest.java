package br.com.engenheiro.api.central;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * F10 (WEB-01..03): o patrão navega pelos três setores com a PRÓPRIA identidade; a ação dele sobre o registro de um
 * setor fica registrada como dele (e pelo site); central de aprovações só com o que o ator pode decidir; busca geral
 * respeitando escopo e campos sensíveis.
 */
@IntegrationTest
class CentralIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    Api api;
    Org org;
    Person patrao;
    Person balcao;
    Person balcao2;   // outra unidade
    Person coord;
    Person contador;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Paula do Balcão");
        balcao2 = fx.person(org, org.branch2(), RoleTemplate.BALCAO, "Balcão da Unidade 2");
        coord = fx.person(org, org.branch1(), RoleTemplate.COORDENADOR_ENTREGAS, "Marcos das Entregas");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Fernanda Contadora");
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    static List<JsonNode> items(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        n.path("items").forEach(out::add);
        return out;
    }

    JsonNode customer(Person who, String name) {
        Api.Res r = api.post("/api/v1/customers", who.token(), Map.of("name", name), (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    JsonNode submittedTax(String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "DAS");
        body.put("description", "Guia fictícia");
        body.put("competence", YearMonth.from(TODAY).toString());
        body.put("due_date", TODAY.plusDays(15).toString());
        body.put("amount", amount);
        JsonNode g = api.postWith("/api/v1/tax-obligations", contador.token(), body, key()).json();
        String id = g.path("id").asString();
        assertThat(api.upload("/api/v1/tax-obligations/" + id + "/attachments", contador.token(), "guia.pdf",
                "application/pdf", PDF).status()).isEqualTo(201);
        Api.Res s = api.post("/api/v1/tax-obligations/" + id + "/submit", contador.token(), null,
                "\"" + g.path("version").asLong() + "\"");
        assertThat(s.status()).as(s.body()).isEqualTo(200);
        return s.json();
    }

    String pendingLoss() {
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA-" + UUID.randomUUID().toString()
                .substring(0, 6), "name", "Cana Fictícia", "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        String product = p.json().path("id").asString();
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio Fictício"), (String) null).json()
                .path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "100", "unit_cost", "10.00"))), key()).json();
        assertThat(api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null,
                key(1)).status()).isEqualTo(200);
        Api.Res l = api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", product, "quantity", "5",
                "reason", "QUEBRA"), key());
        assertThat(l.status()).as(l.body()).isEqualTo(201);
        return l.json().path("id").asString();
    }

    // ---------- Critério: patrão navega pelos três setores sem trocar de identidade; ação de admin = admin ----------
    @Test
    void patraoVeOsTresSetoresComAPropriaIdentidadeEAcaoDeleFicaComoDele() {
        JsonNode doBalcao = customer(balcao, "Cliente do Balcão Fictício");          // app Retirada
        customer(coord, "Cliente das Entregas Fictício");                             // app Entregas
        JsonNode guia = api.postWith("/api/v1/tax-obligations", contador.token(), Map.of("kind", "DAS", "description",
                "Guia fictícia", "competence", YearMonth.from(TODAY).toString(), "due_date", TODAY.plusDays(10).toString(),
                "amount", "850.00"), key()).json();                                    // app Contábil
        // O patrão (site, identidade própria) corrige o cadastro feito no balcão.
        String cid = doBalcao.path("id").asString();
        Api.Res upd = api.put("/api/v1/customers/" + cid, patrao.token(), Map.of("name", "Cliente do Balcão Fictício Ltda"),
                "\"" + doBalcao.path("version").asLong() + "\"");
        assertThat(upd.status()).as(upd.body()).isEqualTo(200);
        assertThat(upd.json().path("name").asString()).isEqualTo("Cliente do Balcão Fictício Ltda");
        // Versão velha → 412 (ninguém sobrescreve às cegas).
        assertThat(api.put("/api/v1/customers/" + cid, patrao.token(), Map.of("name", "Outro"),
                "\"" + doBalcao.path("version").asLong() + "\"").status()).isEqualTo(412);

        // Setor Retirada: criação pelo balcão (app Retirada) e correção pelo PATRÃO (site), não "como o balcão".
        List<JsonNode> counter = items(api.get("/api/v1/audit-events?sector=COUNTER&limit=100", patrao.token()).json());
        JsonNode created = counter.stream().filter(e -> e.path("action").asString().equals("customers.create")
                && e.path("entity_id").asString().equals(cid)).findFirst().orElseThrow();
        assertThat(created.path("actor_user_id").asString()).isEqualTo(balcao.userId().toString());
        assertThat(created.path("client_id").asString()).isEqualTo("engenheiro-retirada");
        JsonNode updated = counter.stream().filter(e -> e.path("action").asString().equals("customers.update")).findFirst()
                .orElseThrow();
        assertThat(updated.path("actor_user_id").asString()).isEqualTo(patrao.userId().toString());
        assertThat(updated.path("actor_name").asString()).isEqualTo("Patrão Fictício");
        assertThat(updated.path("client_id").asString()).isEqualTo("engenheiro-web");
        // O cadastro feito pelo app de Entregas não aparece como balcão; aparece no setor de Entregas.
        assertThat(counter).noneMatch(e -> e.path("actor_user_id").asString().equals(coord.userId().toString()));
        List<JsonNode> delivery = items(api.get("/api/v1/audit-events?sector=DELIVERY&limit=100", patrao.token()).json());
        assertThat(delivery).anyMatch(e -> e.path("actor_user_id").asString().equals(coord.userId().toString()));
        assertThat(delivery).noneMatch(e -> e.path("actor_user_id").asString().equals(balcao.userId().toString()));
        List<JsonNode> accounting = items(api.get("/api/v1/audit-events?sector=ACCOUNTING&limit=100", patrao.token()).json());
        assertThat(accounting).anyMatch(e -> e.path("entity_id").asString().equals(guia.path("id").asString())
                && e.path("actor_user_id").asString().equals(contador.userId().toString())
                && e.path("client_id").asString().equals("engenheiro-contabil"));

        // "Abrir origem": filtro pelo registro devolve a cadeia dele, em ordem, com cada ator.
        List<JsonNode> chain = items(api.get("/api/v1/audit-events?entity_type=customer&entity_id=" + cid, patrao.token())
                .json());
        assertThat(chain).extracting(e -> e.path("action").asString()).containsExactly("customers.update", "customers.create");
        // Período em dia civil de São Paulo [from, to).
        assertThat(items(api.get("/api/v1/audit-events?entity_id=" + cid + "&from=" + TODAY + "&to=" + TODAY.plusDays(1),
                patrao.token()).json())).hasSize(2);
        assertThat(items(api.get("/api/v1/audit-events?entity_id=" + cid + "&from=" + TODAY.plusDays(1), patrao.token())
                .json())).isEmpty();
        // Validação e permissão.
        assertThat(api.get("/api/v1/audit-events?sector=FROTA", patrao.token()).status()).isEqualTo(422);
        assertThat(api.get("/api/v1/audit-events?from=" + TODAY + "&to=" + TODAY, patrao.token()).status()).isEqualTo(422);
        assertThat(api.get("/api/v1/audit-events?sector=COUNTER", balcao.token()).status()).isEqualTo(403);
        // O balcão não edita cadastro (sem customers.edit_draft); campo oculto não pode ser enviado por quem não vê.
        assertThat(api.put("/api/v1/customers/" + cid, balcao.token(), Map.of("name", "X"), "\"2\"").status()).isEqualTo(403);
        fx.grant(balcao, "customers", "edit_draft", "BRANCH", null);
        Api.Res hidden = api.put("/api/v1/customers/" + cid, balcao.token(), Map.of("name", "Cliente do Balcão Fictício Ltda",
                "personal_document", "11111111111"), "\"2\"");
        assertThat(hidden.status()).isEqualTo(422);
        assertThat(hidden.body()).contains("personal_document");
    }

    // ---------- Central de aprovações: o que ESTE ator pode decidir, com as máscaras do módulo ----------
    @Test
    void centralDeAprovacoesSoMostraOQueOAtorPodeDecidir() {
        JsonNode tax = submittedTax("850.00");
        String loss = pendingLoss();

        JsonNode c = api.get("/api/v1/approvals", patrao.token()).json();
        List<JsonNode> it = items(c);
        JsonNode t = it.stream().filter(i -> i.path("kind").asString().equals("TAX")).findFirst().orElseThrow();
        assertThat(t.path("id").asString()).isEqualTo(tax.path("id").asString());
        assertThat(t.path("sector").asString()).isEqualTo("ACCOUNTING");
        assertThat(t.path("amount").asString()).isEqualTo("850.00");
        assertThat(t.path("requested_by").asString()).isEqualTo("Fernanda Contadora");
        assertThat(t.path("note").asString()).contains("não paga");
        JsonNode l = it.stream().filter(i -> i.path("kind").asString().equals("LOSS")).findFirst().orElseThrow();
        assertThat(l.path("id").asString()).isEqualTo(loss);
        assertThat(l.path("sector").asString()).isEqualTo("COUNTER");
        // Custo da perda só é fixado na aprovação (custo médio do momento); pendente não inventa valor.
        assertThat(l.path("amount").isNull()).isTrue();
        assertThat(l.path("detail").asString()).startsWith("5");
        assertThat(l.path("path").asString()).isEqualTo("/operation/stock");
        Map<String, Integer> sectors = new HashMap<>();
        c.path("sectors").forEach(s -> sectors.put(s.path("sector").asString(), s.path("pending").asInt()));
        assertThat(sectors).containsEntry("ACCOUNTING", 1).containsEntry("COUNTER", 1).containsEntry("DELIVERY", 0);

        // Quem prepara a guia não a aprova: a central do contador não traz a guia.
        assertThat(items(api.get("/api/v1/approvals", contador.token()).json()))
                .noneMatch(i -> i.path("kind").asString().equals("TAX"));
        // Balcão sem nenhuma alçada: central vazia (200), não erro.
        Api.Res b = api.get("/api/v1/approvals", balcao.token());
        assertThat(b.status()).isEqualTo(200);
        assertThat(items(b.json())).isEmpty();
        // Gerente que aprova perdas (sem ver custo de compra): vê só a perda, não a guia nem o resto.
        Person gerente = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Gerente Fictício");
        fx.grant(gerente, "inventory.losses", "view", "ORGANIZATION", null);
        fx.grant(gerente, "inventory.losses", "approve", "ORGANIZATION", null);
        List<JsonNode> g = items(api.get("/api/v1/approvals", gerente.token()).json());
        assertThat(g).extracting(i -> i.path("kind").asString()).containsExactly("LOSS");
        assertThat(api.get("/api/v1/approvals", gerente.token()).body()).doesNotContain("850.00");

        // Decidida no módulo, sai da central.
        Api.Res ap = api.postWith("/api/v1/tax-obligations/" + tax.path("id").asString() + "/approval", patrao.token(),
                Map.of("note", "ok"), key(tax.path("version").asLong()));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(items(api.get("/api/v1/approvals", patrao.token()).json()))
                .noneMatch(i -> i.path("kind").asString().equals("TAX"));
    }

    // ---------- Busca geral: escopo de linha e nada de campo sensível ----------
    @Test
    void buscaGeralRespeitaEscopoECamposSensiveis() {
        Api.Res c = api.post("/api/v1/customers", patrao.token(), Map.of("name", "Mercado Busca Fictícia",
                "personal_document", "22222222222", "address", "Rua Secreta Fictícia, 9"), (String) null);
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        api.post("/api/v1/customers", balcao2.token(), Map.of("name", "Quitanda Busca Fictícia"), (String) null);
        Api.Res v = api.postWith("/api/v1/vehicles", patrao.token(), Map.of("plate", "BRA2E19", "model", "Fiorino",
                "capacity_quantity", "300", "capacity_unit", "DUZIA", "odometer_km", "1000"), key());
        assertThat(v.status()).as(v.body()).isEqualTo(201);

        JsonNode all = api.get("/api/v1/search?q=busca", patrao.token()).json();
        assertThat(items(all)).extracting(h -> h.path("title").asString())
                .contains("Mercado Busca Fictícia", "Quitanda Busca Fictícia");
        assertThat(all.toString()).doesNotContain("22222222222").doesNotContain("Rua Secreta");
        JsonNode cust = items(all).stream().filter(h -> h.path("title").asString().equals("Mercado Busca Fictícia"))
                .findFirst().orElseThrow();
        assertThat(cust.path("type").asString()).isEqualTo("customer");
        assertThat(cust.path("path").asString()).startsWith("/sales/customers?q=");
        // Placa (sem diferenciar maiúsculas) leva ao veículo.
        JsonNode plate = items(api.get("/api/v1/search?q=bra2", patrao.token()).json()).getFirst();
        assertThat(plate.path("type").asString()).isEqualTo("vehicle");
        assertThat(plate.path("path").asString()).isEqualTo("/operation/fleet/" + v.json().path("id").asString());
        // Campo sensível não é pesquisável: documento e endereço não encontram nada.
        assertThat(items(api.get("/api/v1/search?q=22222222222", patrao.token()).json())).isEmpty();
        assertThat(items(api.get("/api/v1/search?q=Rua%20Secreta", patrao.token()).json())).isEmpty();
        // Escopo: o balcão da unidade 1 não encontra o cliente da unidade 2, nem veículos (sem vehicles.view).
        JsonNode mine = api.get("/api/v1/search?q=busca", balcao.token()).json();
        assertThat(items(mine)).extracting(h -> h.path("title").asString()).contains("Mercado Busca Fictícia")
                .doesNotContain("Quitanda Busca Fictícia");
        assertThat(mine.path("searched").toString()).doesNotContain("vehicles");
        // Curinga do usuário é literal (não lista tudo).
        assertThat(items(api.get("/api/v1/search?q=%25%25", patrao.token()).json())).isEmpty();
        assertThat(items(api.get("/api/v1/customers?q=%25", patrao.token()).json())).isEmpty();
        // Validação e permissão.
        assertThat(api.get("/api/v1/search?q=a", patrao.token()).status()).isEqualTo(422);
        assertThat(api.get("/api/v1/search?q=" + "x".repeat(81), patrao.token()).status()).isEqualTo(422);
        assertThat(api.get("/api/v1/search?q=busca", contador.token()).status()).isEqualTo(403);
    }

    // ---------- Pedidos: busca por número ou cliente, respeitando o escopo próprio do balcão ----------
    @Test
    void buscaDePedidosPorNumeroOuClienteNoEscopo() {
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA-B" + UUID.randomUUID().toString()
                .substring(0, 4), "name", "Cana Busca", "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        String product = p.json().path("id").asString();
        api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "20.00"), p.header("ETag"));
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio Busca"), (String) null).json()
                .path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "100", "unit_cost", "10.00"))), key()).json();
        api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        String cust = customer(patrao, "Padaria Pedido Fictícia").path("id").asString();
        Api.Res o = api.postWith("/api/v1/orders", patrao.token(), Map.of("type", "PICKUP", "customer_id", cust,
                "items", List.of(Map.of("product_id", product, "quantity", "2"))), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        long number = o.json().path("number").asLong();

        JsonNode byNumber = api.get("/api/v1/search?q=%23" + number, patrao.token()).json();
        assertThat(items(byNumber)).anyMatch(h -> h.path("type").asString().equals("order")
                && h.path("path").asString().equals("/sales/orders/" + o.json().path("id").asString()));
        assertThat(items(api.get("/api/v1/orders?q=padaria", patrao.token()).json())).hasSize(1);
        // Balcão só vê os próprios pedidos: o do patrão não aparece nem pelo número.
        assertThat(items(api.get("/api/v1/orders?q=" + number, balcao.token()).json())).isEmpty();
        assertThat(api.get("/api/v1/search?q=%23" + number, balcao.token()).json().toString())
                .doesNotContain(o.json().path("id").asString());
    }
}
