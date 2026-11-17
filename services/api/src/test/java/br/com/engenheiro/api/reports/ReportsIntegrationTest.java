package br.com.engenheiro.api.reports;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.MutableClock;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** F09: relatório canônico (tela/gráfico/arquivo iguais), fuso comercial, campos ocultos, escopo, metas e mês fechado. */
@IntegrationTest
class ReportsIntegrationTest {
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    Api api;
    Org org;
    Person patrao;
    Person gerente;   // relatório e arquivos, SEM custo de compra/margem
    Person unidade;   // relatório só da própria unidade
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        gerente = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Gerente");
        unidade = fx.person(org, org.branch2(), RoleTemplate.BALCAO, "Gerente da unidade 2");
        for (String a : List.of("view", "export", "print")) {
            fx.grant(gerente, "reports", a, "ORGANIZATION", null);
        }
        fx.grant(gerente, "goals", "view", "ORGANIZATION", null);
        fx.grant(unidade, "reports", "view", "BRANCH", null);
        fx.grant(unidade, "reports", "export", "BRANCH", null);
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

    void receive(String productAndSupplier, String qty, String cost) {
        String[] ps = productAndSupplier.split("\\|");
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", ps[1],
                "lines", List.of(Map.of("product_id", ps[0], "quantity_received", qty, "unit_cost", cost))), key()).json();
        Api.Res c = api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        assertThat(c.status()).as(c.body()).isEqualTo(200);
    }

    /** Pedido pago em dinheiro pelo patrão (caixa aberto); devolve o id. */
    String paidOrder(String productAndSupplier, String qty, String customerId) {
        Api.Res o = api.postWith("/api/v1/orders", patrao.token(), Map.of("type", "PICKUP", "customer_id", customerId,
                "items", List.of(Map.of("product_id", productAndSupplier.split("\\|")[0], "quantity", qty))), key());
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

    void openCash() {
        Api.Res r = api.postWith("/api/v1/cash-sessions", patrao.token(), Map.of("opening_float", "0.00"), key());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
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

    void loss(String productAndSupplier, String qty) {
        Api.Res l = api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", productAndSupplier.split("\\|")[0],
                "quantity", qty, "reason", "DETERIORACAO", "notes", "Perda fictícia"), key());
        assertThat(l.status()).as(l.body()).isEqualTo(201);
        Api.Res ap = api.postWith("/api/v1/inventory/losses/" + l.json().path("id").asString() + "/approve", patrao.token(), null, key(1));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
    }

    static JsonNode fixture(String id) throws Exception {
        JsonNode f = tools.jackson.databind.json.JsonMapper.builder().build().readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../docs/especificacao/testes/dados-financeiros.json")));
        for (JsonNode c : f.path("cases")) {
            if (c.path("id").asString().equals(id)) {
                return c;
            }
        }
        throw new AssertionError(id);
    }

    /** Monta FECHAMENTO-01 pelos fluxos reais: 2.000 dz a R$ 20 (custo 7,50), 4 categorias de despesa, perda de R$ 500. */
    LocalDate buildFixture() {
        LocalDate today = LocalDate.now(SP);
        String cana = product("CANA", "20.00");
        String velha = product("VELHA", "9.00");
        receive(cana, "3000", "7.50");
        receive(velha, "100", "5.00");
        openCash();
        fulfill(paidOrder(cana, "2000", customer));
        expense("FUNCIONARIOS", "8000.00", today);
        expense("VEICULOS", "3000.00", today);
        expense("ESTRUTURA", "2000.00", today);
        expense("IMPOSTOS_TAXAS", "2000.00", today);
        loss(velha, "100");
        return today;
    }

    static String find(JsonNode arr, String key) {
        for (JsonNode n : arr) {
            if (n.path("key").asString().equals(key)) {
                return n.path("amount").asString();
            }
        }
        return null;
    }

    static byte[] bytes(Api.Res r) {
        return r.body().getBytes(StandardCharsets.ISO_8859_1);
    }

    static String pdfText(byte[] pdf) throws Exception {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Todas as células de texto → valor numérico da célula à direita, em todas as abas. */
    static Map<String, Double> xlsx(byte[] b) throws Exception {
        Map<String, Double> out = new HashMap<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(b))) {
            for (Sheet s : wb) {
                for (Row r : s) {
                    var c0 = r.getCell(0);
                    var c1 = r.getCell(1);
                    if (c0 != null && c0.getCellType() == CellType.STRING && c1 != null && c1.getCellType() == CellType.NUMERIC) {
                        out.putIfAbsent(c0.getStringCellValue(), c1.getNumericCellValue());
                    }
                }
            }
        }
        return out;
    }

    // ---------- Critério: 40000 − 15000 − 15000 − 500 = 9500 na tela, no gráfico e nos arquivos ----------
    @Test
    void fixtureDoPacoteResulta9500NaTelaNoGraficoENosArquivos() throws Exception {
        JsonNode fx01 = fixture("FECHAMENTO-01");
        JsonNode exp = fx01.path("expected");
        LocalDate today = buildFixture();
        Api.Res res = api.get("/api/v1/reports/overview?period=MONTH&date=" + today, patrao.token());
        assertThat(res.status()).as(res.body()).isEqualTo(200);
        JsonNode r = res.json();
        JsonNode x = r.path("result");
        assertThat(x.path("net_revenue").asString()).isEqualTo(fx01.path("revenue").asString());
        assertThat(x.path("cogs").asString()).isEqualTo(fx01.path("cogs").asString());
        assertThat(x.path("expenses_total").asString()).isEqualTo(exp.path("expenses_total").asString());
        assertThat(x.path("losses").asString()).isEqualTo(fx01.path("inventory_losses").asString());
        assertThat(x.path("costs_expenses_losses").asString()).isEqualTo(exp.path("costs_expenses_losses_total").asString());
        assertThat(x.path("profit").asString()).isEqualTo(exp.path("profit").asString());
        assertThat(x.path("margin_percent").asString()).isEqualTo(exp.path("margin_percent").asString());
        assertThat(x.path("outcome").asString()).isEqualTo("PROFIT");
        assertThat(r.path("sold").path("quantity_total").asString()).isEqualTo(exp.path("sold_quantity").asString());
        assertThat(r.path("policy_version").asString()).isEqualTo("RESULTADO-GERENCIAL-1");
        assertThat(r.path("timezone").asString()).isEqualTo("America/Sao_Paulo");
        assertThat(r.path("hidden")).isEmpty();
        // Categorias primeiro, com os valores do pacote.
        Map<String, String> cats = Map.of("FUNCIONARIOS", "Funcionários", "VEICULOS", "Veículos", "ESTRUTURA", "Estrutura",
                "IMPOSTOS_TAXAS", "Impostos e taxas");
        cats.forEach((k, label) -> assertThat(find(r.path("expenses"), k))
                .isEqualTo(fx01.path("expense_categories").path(label).asString()));
        // Gráfico: as mesmas strings do fechamento, na mesma ordem.
        assertThat(find(r.path("chart"), "net_revenue")).isEqualTo("40000.00");
        assertThat(find(r.path("chart"), "cogs")).isEqualTo("15000.00");
        assertThat(find(r.path("chart"), "expenses_total")).isEqualTo("15000.00");
        assertThat(find(r.path("chart"), "losses")).isEqualTo("500.00");
        assertThat(find(r.path("chart"), "profit")).isEqualTo("9500.00");
        // Linha do tempo: soma dos dias = total, centavo a centavo.
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        for (JsonNode p : r.path("timeline")) {
            profit = profit.add(new BigDecimal(p.path("profit").asString()));
            revenue = revenue.add(new BigDecimal(p.path("net_revenue").asString()));
        }
        assertThat(profit).isEqualByComparingTo("9500.00");
        assertThat(revenue).isEqualByComparingTo("40000.00");
        assertThat(r.path("timeline_bucket").asString()).isEqualTo("DAY");

        // Arquivos com os mesmos números.
        String q = "?period=MONTH&date=" + today + "&format=";
        Api.Res csv = api.getBytes("/api/v1/reports/overview/export" + q + "csv", patrao.token());
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Disposition")).contains("attachment").contains(".csv");
        assertThat(csv.header("Cache-Control")).contains("no-store");
        String csvText = new String(bytes(csv), StandardCharsets.UTF_8);
        assertThat(csvText).contains("Receita líquida;40000,00").contains("(-) CMV;15000,00").contains("(-) Despesas;15000,00")
                .contains("(-) Perdas;500,00").contains("CMV + despesas + perdas;30500,00").contains("Lucro;9500,00")
                .contains("Funcionários;8000,00").contains("DUZIA;2000,000000");
        Map<String, Double> sheet = xlsx(bytes(api.getBytes("/api/v1/reports/overview/export" + q + "xlsx", patrao.token())));
        assertThat(sheet.get("Receita líquida")).isEqualTo(40000.00);
        assertThat(sheet.get("(-) CMV")).isEqualTo(15000.00);
        assertThat(sheet.get("Lucro")).isEqualTo(9500.00);
        assertThat(sheet.get("Despesas totais")).isEqualTo(15000.00);
        Api.Res pdf = api.getBytes("/api/v1/reports/overview/export" + q + "pdf", patrao.token());
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        String text = pdfText(bytes(pdf));
        assertThat(text).contains("R$ 40.000,00").contains("R$ 15.000,00").contains("R$ 500,00").contains("R$ 9.500,00")
                .contains("R$ 30.500,00").contains("23,75%").contains("America/Sao_Paulo");
        // Cada arquivo gerado fica na auditoria; formato inválido é recusado.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE organization_id = :o AND action IN ('reports.export', 'reports.print')")
                .param("o", org.id()).query(Integer.class).single()).isEqualTo(3);
        assertThat(api.getBytes("/api/v1/reports/overview/export" + q + "docx", patrao.token()).status()).isEqualTo(422);

        // Drilldown até o registro de origem: a receita aponta o pedido; a soma das linhas bate com o total.
        JsonNode lines = api.get("/api/v1/reports/lines?accounts=RECEITA_VENDAS&period=MONTH&date=" + today, patrao.token()).json();
        assertThat(lines.path("net").asString()).isEqualTo("-40000.00");
        assertThat(lines.path("items").get(0).path("origin_type").asString()).isEqualTo("order");
        assertThat(lines.path("items").get(0).path("origin_number").isNumber()).isTrue();
        JsonNode cmv = api.get("/api/v1/reports/lines?accounts=CMV&period=MONTH&date=" + today, patrao.token()).json();
        assertThat(cmv.path("net").asString()).isEqualTo("15000.00");

        // Ranking do cliente: medidas separadas (sem nota única).
        JsonNode rk = api.get("/api/v1/reports/customers?period=MONTH&date=" + today, patrao.token()).json();
        JsonNode c = rk.path("items").get(0);
        assertThat(c.path("net_revenue").asString()).isEqualTo("40000.00");
        assertThat(c.path("margin").asString()).isEqualTo("25000.00");
        assertThat(c.path("quantity").asString()).isEqualTo("2000.000000");
        assertThat(c.path("orders").asInt()).isEqualTo(1);
        assertThat(rk.path("measures").size()).isEqualTo(7);
        assertThat(c.has("score")).isFalse();
    }

    // ---------- Critério: campo oculto não vaza em tela, PDF, CSV, XLSX, drilldown, ranking e metas ----------
    @Test
    void campoOcultoNaoVazaEmNenhumaSaida() throws Exception {
        LocalDate today = buildFixture();
        JsonNode r = api.get("/api/v1/reports/overview?period=MONTH&date=" + today, gerente.token()).json();
        assertThat(r.path("hidden").toString()).contains("cogs", "losses", "costs_expenses_losses", "profit", "margin_percent");
        JsonNode x = r.path("result");
        for (String f : List.of("cogs", "losses", "costs_expenses_losses", "profit", "margin_percent", "outcome")) {
            assertThat(x.path(f).isNull()).as(f).isTrue();
        }
        assertThat(x.path("net_revenue").asString()).isEqualTo("40000.00"); // o que pode ver continua igual ao do patrão
        assertThat(find(r.path("chart"), "cogs")).isNull();
        assertThat(find(r.path("chart"), "profit")).isNull();
        assertThat(r.toString()).doesNotContain("9500").doesNotContain("30500").doesNotContain("23.75");
        for (JsonNode p : r.path("timeline")) {
            assertThat(p.path("profit").isNull()).isTrue();
            assertThat(p.path("cogs").isNull()).isTrue();
        }
        assertThat(r.path("drill").toString()).doesNotContain("CMV").doesNotContain("PERDAS_ESTOQUE");
        String q = "?period=MONTH&date=" + today + "&format=";
        String csv = new String(bytes(api.getBytes("/api/v1/reports/overview/export" + q + "csv", gerente.token())), StandardCharsets.UTF_8);
        assertThat(csv).contains("Receita líquida;40000,00").doesNotContain("9500").doesNotContain("30500").doesNotContain("CMV;")
                .doesNotContain("Perdas;500").doesNotContain("23,75");
        Map<String, Double> sheet = xlsx(bytes(api.getBytes("/api/v1/reports/overview/export" + q + "xlsx", gerente.token())));
        assertThat(sheet).doesNotContainKeys("(-) CMV", "Lucro", "(-) Perdas", "CMV + despesas + perdas");
        assertThat(sheet.values()).doesNotContain(9500.0, 30500.0, 500.0);
        String pdf = pdfText(bytes(api.getBytes("/api/v1/reports/overview/export" + q + "pdf", gerente.token())));
        assertThat(pdf).contains("R$ 40.000,00").doesNotContain("9.500").doesNotContain("30.500").doesNotContain("23,75")
                .doesNotContain("R$ 500,00").contains("Campos ocultos");
        // Linhas a custo, ranking por margem e o resumo antigo também respeitam o campo.
        assertThat(api.get("/api/v1/reports/lines?accounts=CMV&period=MONTH&date=" + today, gerente.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/reports/lines?accounts=RECEITA_VENDAS,PERDAS_ESTOQUE&period=MONTH", gerente.token()).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/finance/ledger-lines?accounts=CMV", gerente.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/reports/customers?sort=margin", gerente.token()).status()).isEqualTo(403);
        JsonNode rk = api.get("/api/v1/reports/customers?period=MONTH&date=" + today, gerente.token()).json();
        assertThat(rk.path("items").get(0).path("margin").isNull()).isTrue();
        assertThat(rk.toString()).doesNotContain("25000");
        JsonNode sum = api.get("/api/v1/reports/summary", gerente.token()).json();
        assertThat(sum.path("profit").isNull()).isTrue();
        assertThat(sum.path("cogs").isNull()).isTrue();
        assertThat(sum.toString()).doesNotContain("9500");
        // Meta de lucro: o gerente vê a meta, não o realizado.
        Api.Res g = api.postWith("/api/v1/goals", patrao.token(), goal("PROFIT", null, "MONTH", today.withDayOfMonth(1), "12000.00"), Map.of());
        assertThat(g.status()).as(g.body()).isEqualTo(201);
        JsonNode viaPatrao = api.get("/api/v1/goals/" + g.json().path("id").asString(), patrao.token()).json();
        assertThat(viaPatrao.path("progress").path("realized").asString()).isEqualTo("9500.00");
        JsonNode viaGerente = api.get("/api/v1/goals/" + g.json().path("id").asString(), gerente.token()).json();
        assertThat(viaGerente.path("progress").path("realized").isNull()).isTrue();
        assertThat(viaGerente.path("progress").path("status").asString()).isEqualTo("HIDDEN");
        assertThat(viaGerente.toString()).doesNotContain("9500");
        // Sem `reports.export`, nada de arquivo (quem só vê na tela).
        Person leitor = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Leitor");
        fx.grant(leitor, "reports", "view", "ORGANIZATION", null);
        assertThat(api.getBytes("/api/v1/reports/overview/export" + q + "csv", leitor.token()).status()).isEqualTo(403);
        assertThat(api.getBytes("/api/v1/reports/overview/export" + q + "pdf", leitor.token()).status()).isEqualTo(403);
    }

    // ---------- Critério: o filtro de dia respeita a fronteira em America/Sao_Paulo ----------
    @Test
    void filtroDeDiaRespeitaFronteiraDeSaoPaulo() {
        String cana = product("CANA", "10.00");
        receive(cana, "100", "4.00");
        openCash();
        String a = paidOrder(cana, "3", customer);
        String b = paidOrder(cana, "5", customer);
        // 23:30 de 31/03 em São Paulo = 02:30 de 01/04 em UTC: a venda pertence ao dia 31/03.
        LocalDate d = LocalDate.now(SP).minusMonths(2).withDayOfMonth(1).minusDays(1);
        try {
            clock.set(d.atTime(23, 30).atZone(SP).toInstant());
            assertThat(OffsetDateTime.ofInstant(clock.instant(), ZoneId.of("UTC")).toLocalDate()).isEqualTo(d.plusDays(1));
            fulfill(a);
            clock.set(d.plusDays(1).atTime(0, 30).atZone(SP).toInstant());
            fulfill(b);
        } finally {
            clock.reset();
        }
        JsonNode day1 = api.get("/api/v1/reports/overview?period=DAY&date=" + d, patrao.token()).json();
        assertThat(day1.path("from").asString()).isEqualTo(d.toString());
        assertThat(day1.path("to").asString()).isEqualTo(d.plusDays(1).toString());
        assertThat(day1.path("result").path("net_revenue").asString()).isEqualTo("30.00");
        assertThat(day1.path("sold").path("quantity_total").asString()).isEqualTo("3.000000");
        JsonNode day2 = api.get("/api/v1/reports/overview?period=DAY&date=" + d.plusDays(1), patrao.token()).json();
        assertThat(day2.path("result").path("net_revenue").asString()).isEqualTo("50.00");
        // Mês e semana somam os dois lados da fronteira conforme o calendário de São Paulo.
        assertThat(api.get("/api/v1/reports/overview?period=MONTH&date=" + d, patrao.token()).json().path("result")
                .path("net_revenue").asString()).isEqualTo("30.00");
        JsonNode custom = api.get("/api/v1/reports/overview?period=CUSTOM&from=" + d + "&to=" + d.plusDays(2), patrao.token()).json();
        assertThat(custom.path("result").path("net_revenue").asString()).isEqualTo("80.00");
        assertThat(custom.path("timeline").size()).isEqualTo(2);
        assertThat(custom.path("timeline").get(0).path("net_revenue").asString()).isEqualTo("30.00");
        LocalDate monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        JsonNode week = api.get("/api/v1/reports/overview?period=WEEK&date=" + d, patrao.token()).json();
        assertThat(week.path("from").asString()).isEqualTo(monday.toString());
        assertThat(week.path("to").asString()).isEqualTo(monday.plusDays(7).toString());
        // Comparação: o dia seguinte compara com o anterior, com fórmula explícita.
        JsonNode cmp = day2.path("comparison");
        assertThat(cmp.path("from").asString()).isEqualTo(d.toString());
        assertThat(find2(cmp.path("items"), "net_revenue").path("difference").asString()).isEqualTo("20.00");
        assertThat(find2(cmp.path("items"), "net_revenue").path("percent").asString()).isEqualTo("66.67");
        assertThat(cmp.path("formula").asString()).contains("anterior");
        // Intervalo inválido.
        assertThat(api.get("/api/v1/reports/overview?period=CUSTOM&from=" + d + "&to=" + d, patrao.token()).status()).isEqualTo(422);
        assertThat(api.get("/api/v1/reports/overview?period=ANO", patrao.token()).status()).isEqualTo(422);

        // Ranking: devolução conta como medida própria; ordenar por devoluções põe quem não devolveu primeiro.
        String outro = api.post("/api/v1/customers", patrao.token(), Map.of("name", "Quitanda Fictícia"), (String) null).json()
                .path("id").asString();
        String c = paidOrder(cana, "10", outro);
        fulfill(c);
        Api.Res ret = api.postWith("/api/v1/orders/" + c + "/returns", patrao.token(), Map.of("reason", "Cana murcha",
                "lines", List.of(Map.of("line_no", 1, "quantity", "2"))), key());
        assertThat(ret.status()).as(ret.body()).isEqualTo(200);
        JsonNode byRevenue = api.get("/api/v1/reports/customers?period=DAY", patrao.token()).json();
        assertThat(byRevenue.path("items").get(0).path("customer_name").asString()).isEqualTo("Quitanda Fictícia");
        assertThat(byRevenue.path("items").get(0).path("net_revenue").asString()).isEqualTo("80.00");
        assertThat(byRevenue.path("items").get(0).path("quantity").asString()).isEqualTo("8.000000");
        assertThat(byRevenue.path("items").get(0).path("returns").asInt()).isEqualTo(1);
    }

    static JsonNode find2(JsonNode arr, String key) {
        for (JsonNode n : arr) {
            if (n.path("key").asString().equals(key)) {
                return n;
            }
        }
        throw new AssertionError(key);
    }

    // ---------- Escopo: unidade presa à própria; ID de outra unidade não abre ----------
    @Test
    void escopoDeUnidadeNoRelatorio() {
        String cana = product("CANA", "10.00");
        receive(cana, "100", "4.00");
        openCash();
        fulfill(paidOrder(cana, "4", customer)); // venda na Matriz (unidade 1)
        JsonNode own = api.get("/api/v1/reports/overview?period=MONTH", unidade.token()).json();
        assertThat(own.path("branch_id").asString()).isEqualTo(org.branch2().toString());
        assertThat(own.path("branch_name").asString()).isEqualTo("Unidade 2");
        assertThat(own.path("result").path("net_revenue").asString()).isEqualTo("0.00");
        assertThat(own.path("cash").isNull()).isTrue(); // caixa da empresa não aparece no escopo de unidade
        assertThat(api.get("/api/v1/reports/overview?branch_id=" + org.branch1(), unidade.token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/reports/overview?branch_id=" + UUID.randomUUID(), patrao.token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/reports/summary", unidade.token()).status()).isEqualTo(403);
        assertThat(api.getBytes("/api/v1/reports/overview/export?format=csv&branch_id=" + org.branch1(), unidade.token()).status())
                .isEqualTo(404);
        // O patrão filtra a Matriz; a soma por unidade = total da empresa.
        JsonNode m = api.get("/api/v1/reports/overview?period=MONTH&branch_id=" + org.branch1(), patrao.token()).json();
        JsonNode all = api.get("/api/v1/reports/overview?period=MONTH", patrao.token()).json();
        assertThat(m.path("result").path("net_revenue").asString()).isEqualTo("40.00");
        assertThat(all.path("result").path("net_revenue").asString()).isEqualTo("40.00");
        // Sem capacidade, ou só com escopo próprio: 403.
        Person sem = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Sem relatório");
        assertThat(api.get("/api/v1/reports/overview", sem.token()).status()).isEqualTo(403);
        fx.grant(sem, "reports", "view", "OWN", null);
        assertThat(api.get("/api/v1/reports/overview", sem.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/goals", sem.token()).status()).isEqualTo(403);
    }

    static Map<String, Object> goal(String metric, String unit, String kind, LocalDate start, String target) {
        Map<String, Object> m = new HashMap<>();
        m.put("metric", metric);
        m.put("unit", unit);
        m.put("period_kind", kind);
        m.put("period_start", start.toString());
        m.put("target", target);
        m.put("title", "Meta fictícia de " + metric.toLowerCase());
        return m;
    }

    // ---------- REP-07: metas com realizado, faltante e fórmula; rascunho editável, ativa imutável ----------
    @Test
    void metasComRealizadoFaltanteEFormula() {
        LocalDate today = buildFixture();
        LocalDate month = today.withDayOfMonth(1);
        Api.Res g = api.postWith("/api/v1/goals", patrao.token(), goal("NET_REVENUE", null, "MONTH", month, "50000.00"), Map.of());
        assertThat(g.status()).as(g.body()).isEqualTo(201);
        JsonNode v = g.json();
        assertThat(v.path("state").asString()).isEqualTo("DRAFT");
        JsonNode p = v.path("progress");
        assertThat(p.path("realized").asString()).isEqualTo("40000.00");
        assertThat(p.path("remaining").asString()).isEqualTo("10000.00");
        assertThat(p.path("percent").asString()).isEqualTo("80.00");
        assertThat(p.path("formula").asString()).contains("Faltante = meta − realizado");
        String id = v.path("id").asString();
        // Rascunho editável com versão; versão velha → 412.
        Map<String, Object> mais = goal("NET_REVENUE", null, "MONTH", month, "60000.00");
        Api.Res e1 = api.put("/api/v1/goals/" + id, patrao.token(), mais, "\"1\"");
        assertThat(e1.status()).as(e1.body()).isEqualTo(200);
        assertThat(e1.json().path("progress").path("remaining").asString()).isEqualTo("20000.00");
        assertThat(api.put("/api/v1/goals/" + id, patrao.token(), mais, "\"1\"").status()).isEqualTo(412);
        // Ativa: não edita mais; uma segunda ativa igual é recusada.
        Api.Res act = api.post("/api/v1/goals/" + id + "/activation", patrao.token(), null, "\"2\"");
        assertThat(act.status()).as(act.body()).isEqualTo(200);
        assertThat(act.json().path("state").asString()).isEqualTo("ACTIVE");
        assertThat(act.json().path("activated_by_name").asString()).isEqualTo("Patrão");
        assertThat(api.put("/api/v1/goals/" + id, patrao.token(), mais, "\"3\"").status()).isEqualTo(409);
        String dup = api.postWith("/api/v1/goals", patrao.token(), mais, Map.of()).json().path("id").asString();
        assertThat(api.post("/api/v1/goals/" + dup + "/activation", patrao.token(), null, "\"1\"").status()).isEqualTo(409);
        // Cancelar exige motivo.
        assertThat(api.post("/api/v1/goals/" + id + "/cancellation", patrao.token(), Map.of("reason", ""), "\"3\"").status()).isEqualTo(422);
        Api.Res cancel = api.post("/api/v1/goals/" + id + "/cancellation", patrao.token(), Map.of("reason", "Meta revista"), "\"3\"");
        assertThat(cancel.status()).as(cancel.body()).isEqualTo(200);
        assertThat(cancel.json().path("cancel_reason").asString()).isEqualTo("Meta revista");
        // Quantidade: 2.000 dz de 1.500 → atingida, faltante 0 (nunca negativo).
        Api.Res qg = api.postWith("/api/v1/goals", patrao.token(), goal("SOLD_QUANTITY", "DUZIA", "MONTH", month, "1500"), Map.of());
        assertThat(qg.json().path("progress").path("status").asString()).isEqualTo("ACHIEVED");
        assertThat(qg.json().path("progress").path("remaining").asString()).isEqualTo("0.000000");
        assertThat(qg.json().path("progress").path("realized").asString()).isEqualTo("2000.000000");
        // Validações: semana começa na segunda; quantidade exige unidade; alvo positivo.
        LocalDate notMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(1);
        assertThat(api.postWith("/api/v1/goals", patrao.token(), goal("NET_REVENUE", null, "WEEK", notMonday, "100.00"), Map.of())
                .status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/goals", patrao.token(), goal("SOLD_QUANTITY", null, "MONTH", month, "10"), Map.of())
                .status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/goals", patrao.token(), goal("NET_REVENUE", null, "MONTH", month, "0"), Map.of())
                .status()).isEqualTo(422);
        // Permissões: só ver não cria; lista com período.
        assertThat(api.postWith("/api/v1/goals", gerente.token(), goal("NET_REVENUE", null, "MONTH", month, "1.00"), Map.of())
                .status()).isEqualTo(403);
        JsonNode list = api.get("/api/v1/goals?from=" + month + "&to=" + month.plusMonths(1), gerente.token()).json();
        assertThat(list.path("items").size()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE organization_id = :o AND action LIKE 'goals.%'")
                .param("o", org.id()).query(Integer.class).single()).isGreaterThanOrEqualTo(6);
    }

    // ---------- Mês fechado: retrato com política versionada, conferido com o livro ----------
    @Test
    void mesFechadoVemDoRetratoComPoliticaVersionada() {
        LocalDate prev = LocalDate.now(SP).withDayOfMonth(1).minusMonths(1);
        expense("ESTRUTURA", "1234.56", prev.plusDays(4));
        Api.Res close = api.post("/api/v1/periods/" + prev.toString().substring(0, 7) + "/close", patrao.token(),
                Map.of("reason", "fechamento mensal"), (String) null);
        assertThat(close.status()).as(close.body()).isEqualTo(200);
        JsonNode r = api.get("/api/v1/reports/overview?period=MONTH&date=" + prev, patrao.token()).json();
        assertThat(r.path("source").asString()).isEqualTo("SNAPSHOT");
        assertThat(r.path("closed").path("policy_version").asString()).isEqualTo("RESULTADO-GERENCIAL-1");
        assertThat(r.path("closed").path("matches_live").asBoolean()).isTrue();
        assertThat(r.path("result").path("expenses_total").asString()).isEqualTo("1234.56");
        assertThat(r.path("result").path("profit").asString()).isEqualTo("-1234.56");
        assertThat(r.path("result").path("outcome").asString()).isEqualTo("LOSS");
        assertThat(find(r.path("chart"), "profit")).isEqualTo("-1234.56");
        String snap = jdbc.sql("SELECT CAST(snapshot AS text) FROM fin_periods WHERE organization_id = :o AND month = :m")
                .param("o", org.id()).param("m", prev).query(String.class).single();
        assertThat(snap).contains("\"policy_version\": \"RESULTADO-GERENCIAL-1\"");
        // A semana desse mês continua do livro (só o mês inteiro tem retrato).
        assertThat(api.get("/api/v1/reports/overview?period=WEEK&date=" + prev.plusDays(4), patrao.token()).json()
                .path("source").asString()).isEqualTo("LIVE");
    }
}
