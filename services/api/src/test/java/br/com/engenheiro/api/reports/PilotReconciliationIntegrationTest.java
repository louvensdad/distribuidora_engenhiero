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

/** F17 (UAT-02): o relatório do período confere com entregas, estoque, caixa e livro; divergência real aparece. */
@IntegrationTest
class PilotReconciliationIntegrationTest {
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person gerente;   // vê o relatório, mas NÃO custo/margem
    Person unidade;   // relatório só da própria unidade
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        gerente = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Gerente");
        unidade = fx.person(org, org.branch2(), RoleTemplate.BALCAO, "Gerente da unidade 2");
        fx.grant(gerente, "reports", "view", "ORGANIZATION", null);
        fx.grant(unidade, "reports", "view", "BRANCH", null);
        customer = api.post("/api/v1/customers", patrao.token(), Map.of("name", "Mercado Fictício"), (String) null).json()
                .path("id").asString();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    String product(String sku, String price) {
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", sku, "name", "Cana " + sku,
                "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        assertThat(p.status()).as(p.body()).isEqualTo(201);
        String id = p.json().path("id").asString();
        api.post("/api/v1/products/" + id + "/prices", patrao.token(), Map.of("price", price), p.header("ETag"));
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio " + sku), (String) null).json()
                .path("id").asString();
        return id + "|" + sup;
    }

    void receive(String ps, String qty, String cost) {
        String[] p = ps.split("\\|");
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", p[1],
                "lines", List.of(Map.of("product_id", p[0], "quantity_received", qty, "unit_cost", cost))), key()).json();
        Api.Res c = api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        assertThat(c.status()).as(c.body()).isEqualTo(200);
    }

    String paidOrder(String ps, String qty) {
        Api.Res o = api.postWith("/api/v1/orders", patrao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(Map.of("product_id", ps.split("\\|")[0], "quantity", qty))), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        String total = o.json().path("total").asString();
        Api.Res p = api.postWith("/api/v1/orders/" + o.json().path("id").asString() + "/payments", patrao.token(),
                Map.of("parts", List.of(Map.of("method", "CASH", "amount", total, "presented_cash", total))), key());
        assertThat(p.status()).as(p.body()).isEqualTo(200);
        return o.json().path("id").asString();
    }

    void fulfill(String orderId) {
        Api.Res f = api.postWith("/api/v1/orders/" + orderId + "/fulfillments", patrao.token(), null, key());
        assertThat(f.status()).as(f.body()).isEqualTo(200);
    }

    void expense(String category, String amount, LocalDate competence) {
        Map<String, Object> body = new HashMap<>();
        body.put("category", category);
        body.put("subcategory", "Geral");
        body.put("description", "Despesa fictícia");
        body.put("amount", amount);
        body.put("competence", competence.toString());
        body.put("due_date", competence.plusDays(10).toString());
        Api.Res e = api.postWith("/api/v1/expenses", patrao.token(), body, key());
        assertThat(e.status()).as(e.body()).isEqualTo(201);
        Api.Res a = api.postWith("/api/v1/expenses/" + e.json().path("id").asString() + "/approve", patrao.token(), Map.of(),
                key(e.json().path("version").asLong()));
        assertThat(a.status()).as(a.body()).isEqualTo(200);
    }

    void loss(String ps, String qty) {
        Api.Res l = api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", ps.split("\\|")[0],
                "quantity", qty, "reason", "DETERIORACAO", "notes", "Perda fictícia"), key());
        assertThat(l.status()).as(l.body()).isEqualTo(201);
        Api.Res ap = api.postWith("/api/v1/inventory/losses/" + l.json().path("id").asString() + "/approve", patrao.token(), null, key(1));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
    }

    static JsonNode check(JsonNode rec, String code) {
        for (JsonNode c : rec.path("checks")) {
            if (c.path("code").asString().equals(code)) {
                return c;
            }
        }
        throw new AssertionError("sem verificação " + code + ": " + rec);
    }

    /** Período piloto pelos fluxos reais: entrega paga, pedido pago e NÃO entregue, despesa e perda. */
    LocalDate pilotPeriod() {
        LocalDate today = LocalDate.now(SP);
        String cana = product("CANA", "20.00");
        String velha = product("VELHA", "9.00");
        receive(cana, "3000", "7.50");
        receive(velha, "100", "5.00");
        Api.Res cash = api.postWith("/api/v1/cash-sessions", patrao.token(), Map.of("opening_float", "100.00"), key());
        assertThat(cash.status()).as(cash.body()).isEqualTo(201);
        fulfill(paidOrder(cana, "2000"));   // venda 40.000, CMV 15.000
        paidOrder(cana, "50");              // pago 1.000, ainda não entregue: adiantamento e reserva, não venda (B03/B04)
        expense("ESTRUTURA", "2000.00", today);
        loss(velha, "100");                 // perda 500, fora do CMV (B11)
        return today;
    }

    @Test
    void periodoPilotoConciliaComRelatorioEstoqueCaixaELivro() {
        LocalDate today = pilotPeriod();
        Api.Res res = api.get("/api/v1/reports/reconciliation?period=MONTH&date=" + today, patrao.token());
        assertThat(res.status()).as(res.body()).isEqualTo(200);
        JsonNode rec = res.json();
        assertThat(rec.path("status").asString()).as(rec.toString()).isEqualTo("OK");
        assertThat(rec.path("timezone").asString()).isEqualTo("America/Sao_Paulo");

        JsonNode report = api.get("/api/v1/reports/overview?period=MONTH&date=" + today, patrao.token()).json().path("result");
        assertThat(rec.path("report").path("net_revenue").asString()).isEqualTo(report.path("net_revenue").asString())
                .isEqualTo("40000.00");
        assertThat(rec.path("report").path("profit").asString()).isEqualTo(report.path("profit").asString());

        assertThat(check(rec, "SALES_NET").path("expected").asString()).isEqualTo("40000.00");
        assertThat(check(rec, "COGS").path("expected").asString()).isEqualTo("15000.00");
        assertThat(check(rec, "SALES_STOCK_QTY").path("actual").asString()).isEqualTo("2000");
        assertThat(check(rec, "LOSSES").path("actual").asString()).isEqualTo("500.00");
        // Dinheiro recebido inclui o pedido pago e não entregue (adiantamento), sem virar venda.
        assertThat(check(rec, "CASH_PAYMENTS").path("expected").asString()).isEqualTo("41000.00");
        assertThat(check(rec, "CASH_PAYMENTS").path("actual").asString()).isEqualTo("41000.00");
        assertThat(check(rec, "RECEIPTS_LEDGER").path("actual").asString()).isEqualTo("41000.00");
        assertThat(check(rec, "STOCK_RESERVED").path("status").asString()).isEqualTo("OK");
        assertThat(check(rec, "LEDGER_SOURCES").path("status").asString()).isEqualTo("OK");
        assertThat(rec.path("report").path("fulfillments").asInt()).isEqualTo(1);
        for (JsonNode c : rec.path("checks")) {
            assertThat(c.path("status").asString()).as(c.toString()).isIn("OK", "ROUNDING");
        }
    }

    @Test
    void quemNaoVeCustoOuSoVeAUnidadeNaoAbreAConciliacao() {
        LocalDate today = pilotPeriod();
        String q = "/api/v1/reports/reconciliation?period=MONTH&date=" + today;
        Api.Res g = api.get(q, gerente.token());
        assertThat(g.status()).as(g.body()).isEqualTo(403);
        assertThat(g.body()).doesNotContain("15000").doesNotContain("cogs");
        assertThat(api.get(q, unidade.token()).status()).isEqualTo(403);
        Person balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balconista");
        assertThat(api.get(q, balcao.token()).status()).isEqualTo(403);
    }

    @Test
    void divergenciaRealNoEstoqueOuNoCaixaApareceSemSerEscondida() {
        LocalDate today = pilotPeriod();
        String q = "/api/v1/reports/reconciliation?period=MONTH&date=" + today;
        // Estoque alterado por fora do sistema (sem movimentação): o saldo deixa de nascer do histórico.
        jdbc.sql("""
                        UPDATE inventory_balances SET physical = physical + 1
                         WHERE branch_id = :b AND product_id = (SELECT id FROM products WHERE organization_id = :o AND sku = 'CANA')
                        """).param("b", org.branch1()).param("o", org.id()).update();
        // Entrada de caixa adulterada: dinheiro no caixa diferente do pagamento confirmado.
        jdbc.sql("""
                        UPDATE cash_movements SET amount = amount - 10
                         WHERE id = (SELECT m.id FROM cash_movements m JOIN cash_sessions s ON s.id = m.cash_session_id
                                      WHERE s.organization_id = :o AND m.source_type = 'payment' ORDER BY m.amount DESC LIMIT 1)
                        """).param("o", org.id()).update();
        JsonNode rec = api.get(q, patrao.token()).json();
        assertThat(rec.path("status").asString()).isEqualTo("DIVERGENT");
        assertThat(check(rec, "STOCK_PHYSICAL").path("status").asString()).isEqualTo("DIVERGENT");
        assertThat(check(rec, "STOCK_PHYSICAL").path("difference").asString()).isEqualTo("1");
        assertThat(check(rec, "CASH_PAYMENTS").path("status").asString()).isEqualTo("DIVERGENT");
        assertThat(check(rec, "CASH_PAYMENTS").path("difference").asString()).isEqualTo("-10.00");
        // O que não foi mexido continua conferindo.
        assertThat(check(rec, "SALES_NET").path("status").asString()).isEqualTo("OK");
        assertThat(check(rec, "LEDGER_SOURCES").path("status").asString()).isEqualTo("OK");
    }
}
