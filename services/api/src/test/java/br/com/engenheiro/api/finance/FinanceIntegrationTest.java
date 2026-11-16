package br.com.engenheiro.api.finance;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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

/** F06: subledger, competência × caixa, despesas, contas a pagar/receber, cartões, transferências, períodos e conciliação. */
@IntegrationTest
class FinanceIntegrationTest {
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
    Person financeiro;
    Person aprovador;
    Person balcao;
    String product;
    String customer;
    String bank;
    String safe;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        financeiro = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Financeiro");
        aprovador = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Aprovador");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
        for (String a : List.of("view", "create", "confirm", "reverse")) {
            fx.grant(financeiro, "expenses", a, "ORGANIZATION", null);
        }
        fx.grant(financeiro, "reports", "view", "ORGANIZATION", null);
        fx.grant(financeiro, "company_cash", "view", "ORGANIZATION", null);
        fx.grant(aprovador, "expenses", "view", "ORGANIZATION", null);
        fx.grant(aprovador, "expenses", "approve", "ORGANIZATION", new BigDecimal("1000.00"));
        fx.grant(aprovador, "reports", "view", "ORGANIZATION", null);
        fx.grant(aprovador, "reports", "reopen_period", "ORGANIZATION", null);
        fx.grant(financeiro, "reports", "close_period", "ORGANIZATION", null);
        fx.grant(financeiro, "reports", "reopen_period", "ORGANIZATION", null);
        for (String a : List.of("create", "confirm", "view")) {
            fx.grant(balcao, "payments", a, "BRANCH", null);
        }
        fx.grant(balcao, "own_cash", "create", "OWN", null);
        fx.grant(balcao, "own_cash", "view", "OWN", null);
        // Produto a R$ 7,50/dúzia; 5.000 dúzias a R$ 3,00.
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA", "name", "Cana caiana",
                "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        product = p.json().path("id").asString();
        api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "7.50"), p.header("ETag"));
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio"), (String) null).json().path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "5000", "unit_cost", "3.00"))), key()).json();
        assertThat(api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null,
                key(1)).status()).isEqualTo(200);
        customer = api.post("/api/v1/customers", balcao.token(), Map.of("name", "Mercado"), (String) null).json()
                .path("id").asString();
        JsonNode accs = api.get("/api/v1/finance/accounts", patrao.token()).json().path("items");
        for (JsonNode a : accs) {
            if (a.path("code").asString().equals("BANCO_PRINCIPAL")) {
                bank = a.path("id").asString();
            }
            if (a.path("code").asString().equals("COFRE_EMPRESA")) {
                safe = a.path("id").asString();
            }
        }
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    JsonNode order(String qty) {
        Api.Res o = api.postWith("/api/v1/orders", balcao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", qty))), key());
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        return o.json();
    }

    JsonNode summary() {
        return api.get("/api/v1/reports/summary?from=" + TODAY.withDayOfMonth(1) + "&to=" + TODAY.withDayOfMonth(1).plusMonths(1),
                patrao.token()).json();
    }

    String expenseOf(JsonNode s, String category) {
        for (JsonNode e : s.path("expenses")) {
            if (e.path("key").asString().equals(category)) {
                return e.path("amount").asString();
            }
        }
        throw new AssertionError(category);
    }

    JsonNode expense(Person who, String category, String amount, LocalDate competence) {
        Map<String, Object> body = new HashMap<>();
        body.put("category", category);
        body.put("subcategory", category.equals("IMPOSTOS_TAXAS") ? "Guia mensal" : "Geral");
        body.put("description", "Despesa de teste");
        body.put("amount", amount);
        body.put("competence", competence.toString());
        body.put("due_date", competence.plusDays(10).toString());
        Api.Res e = api.postWith("/api/v1/expenses", who.token(), body, key());
        assertThat(e.status()).as(e.body()).isEqualTo(201);
        return e.json();
    }

    JsonNode approve(Person who, JsonNode e) {
        Api.Res a = api.postWith("/api/v1/expenses/" + e.path("id").asString() + "/approve", who.token(), Map.of(),
                key(e.path("version").asLong()));
        assertThat(a.status()).as(a.body()).isEqualTo(200);
        return a.json();
    }

    JsonNode reconciliation() {
        return api.get("/api/v1/finance/reconciliation", patrao.token()).json();
    }

    // ---------- Critério: venda de 100 com taxa 3 → receita 100, despesa 3, recebível 97 (FIN-03) ----------
    @Test
    void venda100ComTaxa3ReceitaDespesaERecebivel97EmParcelasConciliadas() {
        JsonNode o = order("13.333334"); // fica R$ 100,00 (13,333334 × 7,50 = 100,000005 → 100,00)
        assertThat(o.path("total").asString()).isEqualTo("100.00");
        String oid = o.path("id").asString();
        Api.Res pay = api.postWith("/api/v1/orders/" + oid + "/payments", balcao.token(), Map.of("parts", List.of(Map.of(
                "method", "CREDIT", "amount", "100.00", "fee", "3.00", "installments", 3, "provider_reference", "NSU-F06-1"))), key());
        assertThat(pay.status()).as(pay.body()).isEqualTo(200);
        assertThat(api.postWith("/api/v1/orders/" + oid + "/fulfillments", patrao.token(), null, key()).status()).isEqualTo(200);

        JsonNode s = summary();
        assertThat(s.path("gross_revenue").asString()).isEqualTo("100.00");
        assertThat(expenseOf(s, "IMPOSTOS_TAXAS")).isEqualTo("3.00");
        // Parcelas líquidas: 97 no total (32,33 + 32,33 + 32,34), bruto 100 e taxa 3 somam exatos.
        JsonNode rs = api.get("/api/v1/receivables?kind=CARD", balcao.token()).json().path("items");
        assertThat(rs).hasSize(3);
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        for (JsonNode r : rs) {
            net = net.add(new BigDecimal(r.path("amount").asString()));
            gross = gross.add(new BigDecimal(r.path("gross").asString()));
            fee = fee.add(new BigDecimal(r.path("fee").asString()));
        }
        assertThat(net).isEqualByComparingTo("97.00");
        assertThat(gross).isEqualByComparingTo("100.00");
        assertThat(fee).isEqualByComparingTo("3.00");
        assertThat(rs.get(2).path("amount").asString()).isEqualTo("32.34");
        JsonNode cash = s.path("cash");
        assertThat(cash.path("card_receivables_open").asString()).isEqualTo("97.00");

        // Repasse da 1ª parcela exato no banco: não gera nova taxa.
        String r1 = rs.get(0).path("id").asString();
        Api.Res st = api.postWith("/api/v1/receivables/" + r1 + "/settlements", balcao.token(), Map.of("fin_account_id", bank), key());
        assertThat(st.status()).as(st.body()).isEqualTo(200);
        assertThat(st.json().path("receivable").path("state").asString()).isEqualTo("SETTLED");
        assertThat(expenseOf(summary(), "IMPOSTOS_TAXAS")).isEqualTo("3.00");
        // Repasse menor sem motivo é recusado; com motivo, a diferença é ajuste de taxa (uma vez).
        String r2 = rs.get(1).path("id").asString();
        Api.Res semMotivo = api.postWith("/api/v1/receivables/" + r2 + "/settlements", balcao.token(),
                Map.of("fin_account_id", bank, "received", "32.00"), key());
        assertThat(semMotivo.status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/receivables/" + r2 + "/settlements", balcao.token(), Map.of("fin_account_id", bank,
                "received", "32.00", "note", "Adquirente cobrou antecipação"), key()).status()).isEqualTo(200);
        assertThat(expenseOf(summary(), "IMPOSTOS_TAXAS")).isEqualTo("3.33");
        // Repasse não entra no caixa de turno (cartão vai para banco) e estorno de pagamento com parcela liquidada é barrado.
        assertThat(api.postWith("/api/v1/receivables/" + rs.get(2).path("id").asString() + "/settlements", balcao.token(),
                Map.of("fin_account_id", safe), key()).status()).isEqualTo(422);
        assertThat(reconciliation().path("status").asString()).isEqualTo("OK");
    }

    // ---------- Critério: pagar obrigação reconhecida não gera segunda despesa (FIN-06, OBRIGACAO-01) ----------
    @Test
    void pagamentoDeObrigacaoReconhecidaNaoGeraSegundaDespesa() {
        JsonNode e = expense(financeiro, "IMPOSTOS_TAXAS", "850.00", TODAY);
        // Quem cadastrou não aprova; alçada do aprovador é 1000.
        assertThat(api.postWith("/api/v1/expenses/" + e.path("id").asString() + "/approve", financeiro.token(), Map.of(),
                key(e.path("version").asLong())).status()).isEqualTo(403);
        JsonNode ap = approve(aprovador, e);
        assertThat(ap.path("state").asString()).isEqualTo("APPROVED");
        assertThat(ap.path("payable_balance").asString()).isEqualTo("850.00");
        assertThat(expenseOf(summary(), "IMPOSTOS_TAXAS")).isEqualTo("850.00");
        String payable = ap.path("payable_id").asString();

        // Banco com saldo inicial (lançado como saldo inicial, não receita).
        Api.Res acc = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco Fictício", "kind", "BANK",
                "opening_balance", "2000.00"), key());
        assertThat(acc.status()).as(acc.body()).isEqualTo(201);
        String bank2 = acc.json().path("id").asString();
        assertThat(api.postWith("/api/v1/finance/accounts", financeiro.token(), Map.of("name", "X", "kind", "BANK"), key())
                .status()).isEqualTo(403);
        Api.Res over = api.postWith("/api/v1/payables/" + payable + "/payments", financeiro.token(), Map.of("amount", "900.00",
                "fin_account_id", bank2), key());
        assertThat(over.status()).isEqualTo(422);
        Api.Res p1 = api.postWith("/api/v1/payables/" + payable + "/payments", financeiro.token(), Map.of("amount", "850.00",
                "fin_account_id", bank2), key());
        assertThat(p1.status()).as(p1.body()).isEqualTo(200);
        assertThat(p1.json().path("payable").path("state").asString()).isEqualTo("PAID");
        assertThat(p1.json().path("payable").path("balance").asString()).isEqualTo("0.00");
        JsonNode s = summary();
        assertThat(expenseOf(s, "IMPOSTOS_TAXAS")).isEqualTo("850.00"); // continua 850, não 1.700
        assertThat(s.path("expenses_total").asString()).isEqualTo("850.00");
        int expenseEntries = jdbc.sql("""
                        SELECT count(*) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND l.account = 'DESPESAS_IMPOSTOS'
                        """).param("o", org.id()).query(Integer.class).single();
        assertThat(expenseEntries).isEqualTo(1);
        // Extrato do banco: 2.000 iniciais − 850 = 1.150.
        JsonNode stmt = api.get("/api/v1/finance/accounts/" + bank2 + "/statement", financeiro.token()).json();
        assertThat(stmt.path("closing_balance").asString()).isEqualTo("1150.00");
        assertThat(stmt.path("lines")).hasSize(2);

        // FIN-08: estornar a despesa paga exige estornar o pagamento antes; depois, despesa volta a zero.
        Api.Res cedo = api.post("/api/v1/expenses/" + e.path("id").asString() + "/reverse", financeiro.token(),
                Map.of("note", "lançada em duplicidade"), "\"" + ap.path("version").asLong() + "\"");
        assertThat(cedo.status()).isEqualTo(409);
        String settlement = p1.json().path("settlements").get(0).path("id").asString();
        Api.Res rev = api.post("/api/v1/payable-settlements/" + settlement + "/reverse", financeiro.token(),
                Map.of("reason", "pago na conta errada"), (String) null);
        assertThat(rev.status()).as(rev.body()).isEqualTo(200);
        assertThat(rev.json().path("payable").path("balance").asString()).isEqualTo("850.00");
        long v = api.get("/api/v1/expenses/" + e.path("id").asString(), financeiro.token()).json().path("version").asLong();
        Api.Res revE = api.post("/api/v1/expenses/" + e.path("id").asString() + "/reverse", financeiro.token(),
                Map.of("note", "lançada em duplicidade"), "\"" + v + "\"");
        assertThat(revE.status()).as(revE.body()).isEqualTo(200);
        assertThat(revE.json().path("state").asString()).isEqualTo("REVERSED");
        assertThat(revE.json().path("payable_state").asString()).isEqualTo("CANCELLED");
        assertThat(expenseOf(summary(), "IMPOSTOS_TAXAS")).isEqualTo("0.00");
        // Nada foi apagado: lançamento original + reversão vinculada.
        int entries = jdbc.sql("""
                        SELECT count(*) FROM journal_entries WHERE source_type = 'expense' AND source_id = :s
                        """).param("s", UUID.fromString(e.path("id").asString())).query(Integer.class).single();
        assertThat(entries).isEqualTo(2);
        assertThat(reconciliation().path("status").asString()).isEqualTo("OK");
        // Alçada: 1.200 acima do limite de 1.000 do aprovador.
        JsonNode grande = expense(financeiro, "ESTRUTURA", "1200.00", TODAY);
        assertThat(api.postWith("/api/v1/expenses/" + grande.path("id").asString() + "/approve", aprovador.token(), Map.of(),
                key(grande.path("version").asLong())).status()).isEqualTo(403);
    }

    @Test
    void doisPagamentosSimultaneosDaMesmaContaSoUmBaixa() throws Exception {
        JsonNode ap = approve(aprovador, expense(financeiro, "ESTRUTURA", "300.00", TODAY));
        String payable = ap.path("payable_id").asString();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            fs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                return api.postWith("/api/v1/payables/" + payable + "/payments", financeiro.token(), Map.of("amount", "300.00",
                        "fin_account_id", bank), key()).status();
            }));
        }
        go.countDown();
        List<Integer> st = fs.stream().map(CompletableFuture::join).toList();
        assertThat(st).containsOnlyOnce(200);
        assertThat(st).containsAnyOf(409, 422); // o segundo vê a conta já paga (409) ou o saldo zerado (422)
        BigDecimal paid = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payable_settlements WHERE payable_id = :p AND kind = 'PAYMENT'")
                .param("p", UUID.fromString(payable)).query(BigDecimal.class).single();
        assertThat(paid).isEqualByComparingTo("300.00");
    }

    // ---------- Critério: transferência entre contas não muda o consolidado (FIN-05) ----------
    @Test
    void transferenciaNaoMudaSaldoConsolidado() {
        Api.Res acc = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco Fictício 2", "kind", "BANK",
                "opening_balance", "1000.00"), key());
        String b2 = acc.json().path("id").asString();
        String before = summary().path("cash").path("available").asString();
        assertThat(before).isEqualTo("1000.00");
        Api.Res t = api.postWith("/api/v1/finance/transfers", patrao.token(), Map.of("from_account_id", b2, "to_account_id", safe,
                "amount", "400.00", "note", "Saque para o cofre"), key());
        assertThat(t.status()).as(t.body()).isEqualTo(201);
        JsonNode cash = summary().path("cash");
        assertThat(cash.path("available").asString()).isEqualTo(before);
        Map<String, String> balances = new HashMap<>();
        for (JsonNode a : api.get("/api/v1/finance/accounts", patrao.token()).json().path("items")) {
            balances.put(a.path("id").asString(), a.path("balance").asString());
        }
        assertThat(balances).containsEntry(b2, "600.00").containsEntry(safe, "400.00");
        // Cofre não fica negativo; transferência só pelo administrador; resultado não muda.
        assertThat(api.postWith("/api/v1/finance/transfers", patrao.token(), Map.of("from_account_id", safe, "to_account_id", b2,
                "amount", "999.00"), key()).status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/finance/transfers", financeiro.token(), Map.of("from_account_id", b2, "to_account_id", safe,
                "amount", "1.00"), key()).status()).isEqualTo(403);
        assertThat(summary().path("profit").asString()).isEqualTo("0.00");
        // Estorno da transferência devolve os saldos.
        Api.Res r = api.post("/api/v1/finance/transfers/" + t.json().path("id").asString() + "/reverse", patrao.token(),
                Map.of("reason", "conta errada"), (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("state").asString()).isEqualTo("REVERSED");
        assertThat(api.get("/api/v1/finance/accounts/" + safe + "/statement", patrao.token()).json().path("closing_balance")
                .asString()).isEqualTo("0.00");
    }

    // ---------- FIN-04 / CAIXA-01: resultado gerencial ≠ caixa disponível ----------
    @Test
    void caixaDisponivelSeparadoDoResultado() {
        // Banco 5.000; recebe 36.000 de venda a prazo; paga 30.000 de despesas → saldo 11.000 (não é o lucro).
        String b = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco CAIXA-01", "kind", "BANK",
                "opening_balance", "5000.00"), key()).json().path("id").asString();
        JsonNode o = order("4800"); // 4.800 dúzias × 7,50 = 36.000
        String oid = o.path("id").asString();
        assertThat(api.postWith("/api/v1/orders/" + oid + "/payments", patrao.token(), Map.of("parts", List.of(Map.of(
                "method", "ON_ACCOUNT", "amount", "36000.00", "due_date", TODAY.plusDays(30).toString()))), key()).status())
                .isEqualTo(200);
        assertThat(api.postWith("/api/v1/orders/" + oid + "/fulfillments", patrao.token(), null, key()).status()).isEqualTo(200);
        JsonNode rec = api.get("/api/v1/receivables?kind=CUSTOMER", balcao.token()).json().path("items").get(0);
        assertThat(rec.path("amount").asString()).isEqualTo("36000.00");
        assertThat(api.postWith("/api/v1/receivables/" + rec.path("id").asString() + "/settlements", balcao.token(),
                Map.of("fin_account_id", b, "received", "35999.00"), key()).status()).isEqualTo(422); // cliente: exato
        assertThat(api.postWith("/api/v1/receivables/" + rec.path("id").asString() + "/settlements", balcao.token(),
                Map.of("fin_account_id", b), key()).status()).isEqualTo(200);
        JsonNode ap = approve(patrao, expense(financeiro, "FUNCIONARIOS", "30000.00", TODAY));
        assertThat(api.postWith("/api/v1/payables/" + ap.path("payable_id").asString() + "/payments", financeiro.token(),
                Map.of("amount", "30000.00", "fin_account_id", b), key()).status()).isEqualTo(200);
        JsonNode stmt = api.get("/api/v1/finance/accounts/" + b + "/statement", financeiro.token()).json();
        assertThat(stmt.path("inflows").asString()).isEqualTo("41000.00");
        assertThat(stmt.path("outflows").asString()).isEqualTo("30000.00");
        assertThat(stmt.path("closing_balance").asString()).isEqualTo("11000.00");
        JsonNode s = summary();
        // Resultado: 36.000 − CMV 14.400 − 30.000 = −8.400; caixa 11.000. Coisas diferentes, lado a lado.
        assertThat(s.path("net_revenue").asString()).isEqualTo("36000.00");
        assertThat(s.path("cogs").asString()).isEqualTo("14400.00");
        assertThat(s.path("profit").asString()).isEqualTo("-8400.00");
        assertThat(s.path("cash").path("available").asString()).isEqualTo("11000.00");
        // Drilldown: cada total abre as linhas que o formam e a soma fecha.
        for (JsonNode d : s.path("drill")) {
            List<String> accs = new ArrayList<>();
            d.path("accounts").forEach(a -> accs.add(a.asString()));
            JsonNode lines = api.get("/api/v1/finance/ledger-lines?accounts=" + String.join(",", accs) + "&from="
                    + s.path("from").asString() + "&to=" + s.path("to").asString(), patrao.token()).json();
            BigDecimal net = new BigDecimal(lines.path("net").asString());
            BigDecimal expected = new BigDecimal(d.path("amount").asString());
            assertThat(d.path("key").asString().equals("gross_revenue") ? net.negate() : net)
                    .as(d.path("key").asString()).isEqualByComparingTo(expected);
        }
        assertThat(reconciliation().path("status").asString()).isEqualTo("OK");
        // Operação não vê o consolidado.
        assertThat(api.get("/api/v1/reports/summary", balcao.token()).status()).isEqualTo(403);
        // Quem só recebe vê as contas para escolher, sem saldo (consolidado bloqueado).
        JsonNode semSaldo = api.get("/api/v1/finance/accounts", balcao.token()).json().path("items");
        assertThat(semSaldo.size()).isGreaterThan(0);
        semSaldo.forEach(a -> assertThat(a.path("balance").isNull()).isTrue());
        assertThat(semSaldo.toString()).doesNotContain("CAIXAS_TURNO");
        assertThat(api.get("/api/v1/finance/accounts", aprovador.token()).status()).isEqualTo(403);
    }

    // ---------- FIN-07: fechamento e reabertura aprovada, com histórico ----------
    @Test
    void periodoFechadoBloqueiaLancamentoEReaberturaExigeAprovacao() {
        LocalDate prev = TODAY.withDayOfMonth(1).minusMonths(1);
        String month = YearMonth.from(prev).toString();
        approve(aprovador, expense(financeiro, "ESTRUTURA", "200.00", prev.plusDays(4)));
        assertThat(api.post("/api/v1/periods/" + YearMonth.from(TODAY) + "/close", financeiro.token(), Map.of(), (String) null)
                .status()).isEqualTo(409); // mês corrente não fecha
        assertThat(api.post("/api/v1/periods/" + month + "/close", balcao.token(), Map.of(), (String) null).status())
                .isEqualTo(403);
        Api.Res close = api.post("/api/v1/periods/" + month + "/close", financeiro.token(), Map.of("reason", "fechamento mensal"),
                (String) null);
        assertThat(close.status()).as(close.body()).isEqualTo(200);
        assertThat(close.json().path("state").asString()).isEqualTo("CLOSED");
        assertThat(close.json().path("profit_at_close").asString()).isEqualTo("-200.00");
        // Mês fechado: nova despesa com competência nele é recusada; o banco também recusa direto.
        Map<String, Object> body = Map.of("category", "ESTRUTURA", "subcategory", "Aluguel", "description", "x",
                "amount", "10.00", "competence", prev.plusDays(5).toString());
        Api.Res blocked = api.postWith("/api/v1/expenses", financeiro.token(), body, key());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.json().path("code").asString()).isEqualTo("PERIOD_CLOSED");
        Throwable direct = null;
        try {
            jdbc.sql("""
                            INSERT INTO journal_entries (id, organization_id, branch_id, source_type, source_id, event_kind,
                                                         entry_date, competence, created_by)
                            VALUES (gen_random_uuid(), :o, :b, 'teste', gen_random_uuid(), 'X', CURRENT_DATE, :c, :u)
                            """).param("o", org.id()).param("b", org.branch1()).param("c", prev.plusDays(3))
                    .param("u", patrao.userId()).update();
        } catch (RuntimeException ex) {
            direct = ex;
        }
        assertThat(direct).isNotNull();
        assertThat(direct.getMessage()).contains("período fechado");
        // Reabertura: pedido com motivo; quem pediu não aprova; outra pessoa aprova.
        assertThat(api.post("/api/v1/periods/" + month + "/reopen", aprovador.token(), Map.of(), (String) null).status())
                .isEqualTo(409); // sem solicitação
        Api.Res req = api.post("/api/v1/periods/" + month + "/reopen-requests", financeiro.token(),
                Map.of("reason", "nota de aluguel chegou atrasada"), (String) null);
        assertThat(req.status()).as(req.body()).isEqualTo(200);
        assertThat(req.json().path("reopen_pending").asBoolean()).isTrue();
        assertThat(api.post("/api/v1/periods/" + month + "/reopen", financeiro.token(), Map.of(), (String) null).status())
                .isEqualTo(403);
        Api.Res ok = api.post("/api/v1/periods/" + month + "/reopen", aprovador.token(), Map.of("reason", "ok"), (String) null);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().path("state").asString()).isEqualTo("OPEN");
        List<String> hist = new ArrayList<>();
        ok.json().path("events").forEach(ev -> hist.add(ev.path("action").asString() + "/" + ev.path("actor_name").asString()));
        assertThat(hist).containsExactly("CLOSE/Financeiro", "REOPEN_REQUEST/Financeiro", "REOPEN_APPROVE/Aprovador");
        assertThat(api.postWith("/api/v1/expenses", financeiro.token(), body, key()).status()).isEqualTo(201);
    }

    @Test
    void fechamentoBloqueadoComConciliacaoDivergente() {
        // Conta a pagar sem lançamento no livro (inserida fora do sistema) → divergência detectada.
        jdbc.sql("""
                        INSERT INTO payables (id, organization_id, branch_id, source_type, source_id, description, competence,
                                              amount, balance, created_by)
                        VALUES (gen_random_uuid(), :o, :b, 'manual', gen_random_uuid(), 'fora do livro', CURRENT_DATE, 10, 10, :u)
                        """).param("o", org.id()).param("b", org.branch1()).param("u", patrao.userId()).update();
        JsonNode rec = reconciliation();
        assertThat(rec.path("status").asString()).isEqualTo("DIVERGENT");
        String month = YearMonth.from(TODAY.withDayOfMonth(1).minusMonths(1)).toString();
        Api.Res close = api.post("/api/v1/periods/" + month + "/close", patrao.token(), Map.of(), (String) null);
        assertThat(close.status()).isEqualTo(409);
        assertThat(close.body()).contains("Contas a pagar");
    }

    // ---------- Devolução ao fornecedor abate a conta a pagar; excedente vira crédito ----------
    @Test
    void devolucaoAoFornecedorAbateContaAPagarEConciliacaoFecha() {
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio 2"), (String) null).json().path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "100", "unit_cost", "2.00"))), key()).json();
        String rid = r.path("id").asString();
        api.postWith("/api/v1/inventory/receipts/" + rid + "/confirm", patrao.token(), null, key(1));
        JsonNode pay = api.get("/api/v1/payables?state=PENDING", patrao.token()).json().path("items");
        String payable = null;
        for (JsonNode p : pay) {
            if (p.path("source_id").asString().equals(rid)) {
                payable = p.path("id").asString();
            }
        }
        assertThat(api.postWith("/api/v1/payables/" + payable + "/payments", patrao.token(), Map.of("amount", "150.00",
                "fin_account_id", bank), key()).status()).isEqualTo(200);
        // Devolve 40 dúzias (R$ 80): abate os 50 restantes e gera R$ 30 de crédito com o fornecedor.
        Api.Res ret = api.postWith("/api/v1/inventory/receipts/" + rid + "/returns", patrao.token(), Map.of("line_no", 1,
                "quantity", "40", "reason", "Cana murcha"), key());
        assertThat(ret.status()).as(ret.body()).isEqualTo(200);
        JsonNode det = api.get("/api/v1/payables/" + payable, patrao.token()).json();
        assertThat(det.path("payable").path("state").asString()).isEqualTo("PAID");
        JsonNode credit = api.get("/api/v1/receivables?kind=SUPPLIER_CREDIT", patrao.token()).json().path("items").get(0);
        assertThat(credit.path("amount").asString()).isEqualTo("30.00");
        assertThat(reconciliation().path("status").asString()).isEqualTo("OK");
    }

    // ---------- Resultado pelo cálculo único (FECHAMENTO-01 de testes/dados-financeiros.json) ----------
    @Test
    void calculoDoResultadoBateComOFixtureDoPacote() throws Exception {
        JsonNode fixture = tools.jackson.databind.json.JsonMapper.builder().build().readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../../docs/especificacao/testes/dados-financeiros.json")));
        JsonNode c = null;
        for (JsonNode x : fixture.path("cases")) {
            if (x.path("id").asString().equals("FECHAMENTO-01")) {
                c = x;
            }
        }
        Map<String, BigDecimal> exp = new java.util.LinkedHashMap<>();
        c.path("expense_categories").properties().forEach(e -> exp.put(e.getKey(), new BigDecimal(e.getValue().asString())));
        FinanceReportService.Result r = FinanceReportService.compute(new BigDecimal(c.path("revenue").asString()),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(c.path("cogs").asString()), exp,
                new BigDecimal(c.path("inventory_losses").asString()));
        JsonNode e = c.path("expected");
        assertThat(r.expensesTotal()).isEqualByComparingTo(e.path("expenses_total").asString());
        assertThat(r.costsExpensesLosses()).isEqualByComparingTo(e.path("costs_expenses_losses_total").asString());
        assertThat(r.profit()).isEqualByComparingTo(e.path("profit").asString());
        assertThat(r.marginPercent()).isEqualByComparingTo(e.path("margin_percent").asString());
    }
}
