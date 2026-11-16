package br.com.engenheiro.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** F04: pedido, reserva, pagamentos, caixa, cumprimento parcial, cancelamento, devolução e livro financeiro. */
@IntegrationTest
class OrdersIntegrationTest {
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
    Person balcao2;
    String product;
    String customer;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
        balcao2 = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão 2");
        for (Person b : List.of(balcao, balcao2)) {
            fx.grant(b, "payments", "create", "BRANCH", null);
            fx.grant(b, "payments", "confirm", "BRANCH", null);
            fx.grant(b, "payments", "view", "BRANCH", null);
            fx.grant(b, "own_cash", "view", "OWN", null);
            fx.grant(b, "own_cash", "create", "OWN", null);
            fx.grant(b, "own_cash", "close", "OWN", null);
            fx.grant(b, "orders.pickup", "confirm", "OWN", null);
            fx.grant(b, "orders.pickup", "cancel", "OWN", null);
        }
        // Produto em dúzias, preço R$ 7,50/dúzia, 100 dúzias a custo 3,00.
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA", "name", "Cana caiana",
                "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        product = p.json().path("id").asString();
        assertThat(api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "7.50"),
                p.header("ETag")).status()).isEqualTo(200);
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio"), (String) null).json()
                .path("id").asString();
        Map<String, Object> line = Map.of("product_id", product, "quantity_received", "100", "unit_cost", "3.00");
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(line)), key()).json();
        api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        customer = api.post("/api/v1/customers", balcao.token(), Map.of("name", "Ana Santos"), (String) null).json()
                .path("id").asString();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    JsonNode order(Person who, String qty, String discount) {
        Map<String, Object> item = new HashMap<>();
        item.put("product_id", product);
        item.put("quantity", qty);
        item.put("requested_discount", discount);
        item.put("unit_price", "0.01"); // ignorado: preço é do servidor
        Api.Res r = api.postWith("/api/v1/orders", who.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(item)), key());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    Api.Res pay(Person who, String orderId, Map<String, String> k, Map<String, Object>... parts) {
        return api.postWith("/api/v1/orders/" + orderId + "/payments", who.token(), Map.of("parts", List.of(parts)), k);
    }

    static Map<String, Object> cash(String amount, String presented) {
        return Map.of("method", "CASH", "amount", amount, "presented_cash", presented);
    }

    JsonNode openCash(Person who, String fund) {
        Api.Res r = api.postWith("/api/v1/cash-sessions", who.token(), Map.of("opening_float", fund), key());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    BigDecimal balance(String account) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND l.account = :a
                        """).param("o", org.id()).param("a", account).query(BigDecimal.class).single();
    }

    JsonNode stock() {
        return api.get("/api/v1/inventory/balances", patrao.token()).json().path("items").get(0);
    }

    // ---------- ORD-01/02/03: pedido, reserva, dinheiro e troco (B02, B03) ----------
    @Test
    void pedidoDe75PagoCom100DaTroco25ECaixaSobe75() {
        JsonNode o = order(balcao, "10", null);
        assertThat(o.path("total").asString()).isEqualTo("75.00"); // 10 × 7,50 do servidor
        assertThat(o.path("state").asString()).isEqualTo("CONFIRMED");
        assertThat(o.path("payment_state").asString()).isEqualTo("UNPAID");
        assertThat(o.path("number").asLong()).isEqualTo(1);
        assertThat(stock().path("reserved").asString()).isEqualTo("10");
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("0"); // pedido não é venda (B03)

        // Sem caixa aberto, dinheiro não entra.
        Api.Res semCaixa = pay(balcao, o.path("id").asString(), key(), cash("75.00", "100.00"));
        assertThat(semCaixa.status()).isEqualTo(409);
        assertThat(semCaixa.body()).contains("Abra o seu caixa");

        JsonNode s = openCash(balcao, "50.00");
        Api.Res r = pay(balcao, o.path("id").asString(), key(), cash("75.00", "100.00"));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode p = r.json().path("payments").get(0);
        assertThat(p.path("change_given").asString()).isEqualTo("25.00");
        assertThat(p.path("state").asString()).isEqualTo("CONFIRMED");
        assertThat(r.json().path("order").path("payment_state").asString()).isEqualTo("PAID");
        assertThat(r.json().path("order").path("state").asString()).isEqualTo("CONFIRMED"); // situações separadas
        JsonNode cur = api.get("/api/v1/cash-sessions/current", balcao.token()).json().path("session");
        assertThat(cur.path("cash_in").asString()).isEqualTo("75.00");
        assertThat(cur.path("expected").asString()).isEqualTo("125.00"); // 50 + 75, sem os 25 de troco
        assertThat(balance("CAIXA")).isEqualByComparingTo("75");
        assertThat(balance("CLIENTES")).isEqualByComparingTo("-75"); // adiantamento até a retirada (B04)
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("0");

        // Retirada: reconhece a venda sem segunda entrada de caixa.
        Api.Res f = api.postWith("/api/v1/orders/" + o.path("id").asString() + "/fulfillments", balcao.token(), null, key());
        assertThat(f.status()).as(f.body()).isEqualTo(200);
        assertThat(f.json().path("state").asString()).isEqualTo("FULFILLED");
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("-75");
        assertThat(balance("CLIENTES")).isEqualByComparingTo("0");
        assertThat(balance("CAIXA")).isEqualByComparingTo("75");
        assertThat(balance("CMV")).isEqualByComparingTo("30"); // 10 × 3,00
        assertThat(stock().path("physical").asString()).isEqualTo("90");
        assertThat(stock().path("reserved").asString()).isEqualTo("0");

        // Fechamento: contado 125 → sem diferença; conferido pelo patrão, não pelo operador.
        Api.Res c = api.postWith("/api/v1/cash-sessions/" + s.path("id").asString() + "/close", balcao.token(),
                Map.of("counted", "120.00"), key(s.path("version").asLong() + 1));
        assertThat(c.status()).isEqualTo(422); // diferença de -5 sem justificativa
        long v = api.get("/api/v1/cash-sessions/current", balcao.token()).json().path("session").path("version").asLong();
        Api.Res ok = api.postWith("/api/v1/cash-sessions/" + s.path("id").asString() + "/close", balcao.token(),
                Map.of("counted", "120.00", "justification", "Troco dado a mais"), key(v));
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().path("difference").asString()).isEqualTo("-5.00");
        assertThat(api.post("/api/v1/cash-sessions/" + s.path("id").asString() + "/review", balcao.token(),
                Map.of("note", "ok"), ok.header("ETag")).status()).isEqualTo(403);
        Api.Res rev = api.post("/api/v1/cash-sessions/" + s.path("id").asString() + "/review", patrao.token(),
                Map.of("note", "Conferido"), ok.header("ETag"));
        assertThat(rev.json().path("reviewed_by_name").asString()).isEqualTo("Patrão");
        assertThat(rev.json().path("state").asString()).isEqualTo("REVIEWED");
        // Histórico do pedido para quem vê o pedido (sem precisar de audit.view).
        List<String> etapas = api.get("/api/v1/orders/" + o.path("id").asString() + "/history", balcao.token()).json()
                .path("items").valueStream().map(n -> n.path("action").asString()).toList();
        assertThat(etapas).containsSubsequence("orders.create", "payments.register", "orders.fulfill");
        assertThat(api.get("/api/v1/orders/" + o.path("id").asString() + "/history", balcao2.token()).status())
                .isEqualTo(404);
    }

    @Test
    void precoDoServidorEDescontoPorAlcada() {
        JsonNode o = order(patrao, "2", "1.00"); // administrador: sem limite
        assertThat(o.path("gross_total").asString()).isEqualTo("15.00");
        assertThat(o.path("total").asString()).isEqualTo("14.00");
        // Balcão sem alçada explícita: desconto negado e auditado.
        Map<String, Object> item = Map.of("product_id", product, "quantity", "2", "requested_discount", "0.50");
        Api.Res neg = api.postWith("/api/v1/orders", balcao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(item)), key());
        assertThat(neg.status()).isEqualTo(403);
        assertThat(neg.body()).contains("alçada");
        fx.grant(balcao, "orders.pickup", "edit_draft", "OWN", new BigDecimal("1.00"));
        assertThat(api.postWith("/api/v1/orders", balcao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(item)), key()).status()).isEqualTo(201);
        Map<String, Object> grande = Map.of("product_id", product, "quantity", "2", "requested_discount", "1.01");
        assertThat(api.postWith("/api/v1/orders", balcao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(grande)), key()).status()).isEqualTo(403);
        // Sem preço cadastrado ou sem estoque: recusado.
        Map<String, Object> demais = Map.of("product_id", product, "quantity", "1000");
        Api.Res sem = api.postWith("/api/v1/orders", balcao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(demais)), key());
        assertThat(sem.status()).isEqualTo(409);
        assertThat(sem.body()).contains("STOCK_INSUFFICIENT");
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE organization_id = :o").param("o", org.id())
                .query(Integer.class).single()).isEqualTo(2); // o recusado não deixou pedido nem reserva
        assertThat(stock().path("reserved").asString()).isEqualTo("4");
    }

    // ---------- ORD-04/05: Pix pendente, dividido (B07, B09) ----------
    @Test
    void pixSoComprovanteNaoPagaEDivididoPrecisaFecharOValor() {
        JsonNode o = order(balcao, "20", null); // 150,00
        String id = o.path("id").asString();
        openCash(balcao, "0.00");
        // 99 ou 101 rejeitados.
        assertThat(pay(balcao, id, key(), cash("60.00", "60.00"),
                Map.of("method", "PIX", "amount", "89.00")).status()).isEqualTo(422);
        assertThat(pay(balcao, id, key(), cash("60.00", "60.00"),
                Map.of("method", "PIX", "amount", "91.00")).status()).isEqualTo(422);
        // Confirmação por provedor: NÃO CONFIGURADO, nada gravado.
        Api.Res prov = pay(balcao, id, key(), Map.of("method", "PIX", "amount", "150.00", "confirmation_kind", "PROVIDER"));
        assertThat(prov.status()).isEqualTo(503);
        assertThat(prov.body()).contains("NÃO CONFIGURADO");
        // 60 dinheiro + 90 Pix: Pix fica pendente.
        Api.Res r = pay(balcao, id, key(), cash("60.00", "100.00"), Map.of("method", "PIX", "amount", "90.00"));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode pix = r.json().path("payments").get(1);
        assertThat(pix.path("state").asString()).isEqualTo("PENDING");
        assertThat(r.json().path("order").path("payment_state").asString()).isEqualTo("PARTIALLY_PAID");
        assertThat(r.json().path("order").path("pending_amount").asString()).isEqualTo("90.00");
        assertThat(balance("BANCO")).isEqualByComparingTo("0");
        // Pix pendente não libera a retirada de tudo.
        Api.Res tudo = api.postWith("/api/v1/orders/" + id + "/fulfillments", balcao.token(), null, key());
        assertThat(tudo.status()).isEqualTo(409);
        assertThat(tudo.body()).contains("Pagamento pendente");
        // Conferência manual exige nota; depois confirma uma vez só.
        String pid = pix.path("id").asString();
        assertThat(api.postWith("/api/v1/payments/" + pid + "/confirm", balcao.token(), Map.of(), key(1)).status())
                .isEqualTo(422);
        Map<String, String> k = key(1);
        Api.Res c1 = api.postWith("/api/v1/payments/" + pid + "/confirm", balcao.token(), Map.of("note", "Extrato conferido"), k);
        Api.Res c2 = api.postWith("/api/v1/payments/" + pid + "/confirm", balcao.token(), Map.of("note", "Extrato conferido"), k);
        assertThat(c1.status()).isEqualTo(200);
        assertThat(c2.status()).isEqualTo(200);
        assertThat(api.postWith("/api/v1/payments/" + pid + "/confirm", balcao.token(), Map.of("note", "de novo"),
                key(2)).status()).isEqualTo(409);
        assertThat(balance("BANCO")).isEqualByComparingTo("90");
        assertThat(api.get("/api/v1/orders/" + id, balcao.token()).json().path("payment_state").asString()).isEqualTo("PAID");
        // Pagar de novo: saldo devedor é zero.
        assertThat(pay(balcao, id, key(), cash("1.00", "1.00")).status()).isEqualTo(422);
    }

    // ---------- B08: cartão bruto 100, taxa 3 ----------
    @Test
    void cartaoReceitaBrutaTaxaUmaVezRecebivelLiquido() {
        String id = order(patrao, "40", "200.00").path("id").asString(); // 300 - 200 = 100
        Map<String, Object> card = Map.of("method", "CREDIT", "amount", "100.00", "fee", "3.00",
                "provider_reference", "NSU-123", "installments", 2);
        Api.Res r = pay(patrao, id, key(), card);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), null, key());
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("-300");
        assertThat(balance("DESCONTOS_CONCEDIDOS")).isEqualByComparingTo("200");
        assertThat(balance("TAXAS_CARTAO")).isEqualByComparingTo("3");
        assertThat(balance("RECEBIVEL_CARTAO")).isEqualByComparingTo("97");
        assertThat(balance("CLIENTES")).isEqualByComparingTo("0");
        // NSU repetido não registra a mesma transação duas vezes.
        String outro = order(patrao, "1", null).path("id").asString();
        assertThat(pay(patrao, outro, key(), Map.of("method", "CREDIT", "amount", "7.50", "provider_reference", "NSU-123"))
                .status()).isEqualTo(409);
    }

    // ---------- ORD-07/10: parcial, repetição e concorrência (B05, B06) ----------
    @Test
    void entregaParcialReconheceSoOEntregueERepeticaoNaoDuplica() {
        JsonNode o = order(patrao, "10", null); // 75,00
        String id = o.path("id").asString();
        openCash(patrao, "0.00");
        pay(patrao, id, key(), cash("75.00", "75.00"));
        Map<String, String> k = key();
        Map<String, Object> seis = Map.of("lines", List.of(Map.of("line_no", 1, "quantity", "6")));
        Api.Res f1 = api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), seis, k);
        Api.Res f2 = api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), seis, k); // após "timeout"
        assertThat(f1.status()).isEqualTo(200);
        assertThat(f2.status()).isEqualTo(200);
        assertThat(f1.json().path("state").asString()).isEqualTo("PARTIALLY_FULFILLED");
        assertThat(f1.json().path("lines").get(0).path("remaining_quantity").asString()).isEqualTo("4");
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("-45"); // 6 × 7,50
        assertThat(balance("CMV")).isEqualByComparingTo("18");            // 6 × 3,00
        assertThat(stock().path("physical").asString()).isEqualTo("94");
        assertThat(stock().path("reserved").asString()).isEqualTo("4");
        assertThat(jdbc.sql("SELECT count(*) FROM stock_movements WHERE kind = 'SALE_ISSUE' AND organization_id = :o")
                .param("o", org.id()).query(Integer.class).single()).isEqualTo(1);
        // Restante: fecha a linha com o resto exato do valor.
        Api.Res f3 = api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), null, key());
        assertThat(f3.json().path("state").asString()).isEqualTo("FULFILLED");
        assertThat(balance("RECEITA_VENDAS")).isEqualByComparingTo("-75");
        assertThat(api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), null, key()).status())
                .isEqualTo(409);
    }

    @RepeatedTest(3)
    void retiradasConcorrentesDoMesmoPedidoBaixamUmaVez() throws Exception {
        String id = order(patrao, "4", null).path("id").asString();
        openCash(patrao, "0.00");
        pay(patrao, id, key(), cash("30.00", "30.00"));
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), null, key()).status();
            }));
        }
        start.countDown();
        List<Integer> st = fs.stream().map(CompletableFuture::join).toList();
        assertThat(st).containsOnlyOnce(200);
        assertThat(balance("CMV")).isEqualByComparingTo("12");
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillments f JOIN orders o ON o.id = f.order_id WHERE o.id = :o")
                .param("o", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);
    }

    // ---------- ORD-06/07: cancelamento, devolução e estorno (B12) ----------
    @Test
    void cancelarLiberaReservaEVendaCumpridaSoComDevolucao() {
        JsonNode o = order(balcao, "8", null);
        String id = o.path("id").asString();
        assertThat(stock().path("reserved").asString()).isEqualTo("8");
        assertThat(api.postWith("/api/v1/orders/" + id + "/cancellations", balcao.token(), Map.of(), key(o.path("version").asLong()))
                .status()).isEqualTo(422); // motivo obrigatório
        Api.Res c = api.postWith("/api/v1/orders/" + id + "/cancellations", balcao.token(),
                Map.of("reason", "Cliente desistiu"), key(o.path("version").asLong()));
        assertThat(c.status()).as(c.body()).isEqualTo(200);
        assertThat(c.json().path("state").asString()).isEqualTo("CANCELLED");
        assertThat(stock().path("reserved").asString()).isEqualTo("0");

        // Venda cumprida: cancelar não vale; devolução formal volta estoque pelo custo histórico e estorna a receita.
        String v = order(patrao, "10", null).path("id").asString();
        JsonNode s = openCash(patrao, "0.00");
        String pay = pay(patrao, v, key(), cash("75.00", "75.00")).json().path("payments").get(0).path("id").asString();
        JsonNode done = api.postWith("/api/v1/orders/" + v + "/fulfillments", patrao.token(), null, key()).json();
        assertThat(api.postWith("/api/v1/orders/" + v + "/cancellations", patrao.token(), Map.of("reason", "x"),
                key(done.path("version").asLong())).status()).isEqualTo(409);
        Api.Res ret = api.postWith("/api/v1/orders/" + v + "/returns", patrao.token(), Map.of("reason", "Cana murcha",
                "lines", List.of(Map.of("line_no", 1, "quantity", "2"))), key());
        assertThat(ret.status()).as(ret.body()).isEqualTo(200);
        assertThat(ret.json().path("payment_state").asString()).isEqualTo("REFUND_DUE");
        assertThat(ret.json().path("balance_due").asString()).isEqualTo("-15.00");
        assertThat(balance("DEVOLUCOES_VENDAS")).isEqualByComparingTo("15");
        assertThat(balance("CMV")).isEqualByComparingTo("24"); // 30 − 6 devolvidos ao custo histórico
        assertThat(stock().path("physical").asString()).isEqualTo("92");
        assertThat(jdbc.sql("SELECT count(*) FROM stock_movements WHERE kind = 'SALE_RETURN' AND reversal_of IS NOT NULL "
                + "AND organization_id = :o").param("o", org.id()).query(Integer.class).single()).isEqualTo(1);
        // Devolver mais que o entregue: recusado.
        assertThat(api.postWith("/api/v1/orders/" + v + "/returns", patrao.token(), Map.of("reason", "x",
                "lines", List.of(Map.of("line_no", 1, "quantity", "9"))), key()).status()).isEqualTo(422);
        // Estorno do pagamento em dinheiro: sai do caixa, com alçada.
        Api.Res est = api.postWith("/api/v1/payments/" + pay + "/reversals", patrao.token(), Map.of("note", "Devolução"),
                key(1));
        assertThat(est.status()).as(est.body()).isEqualTo(200);
        assertThat(api.get("/api/v1/cash-sessions/current", patrao.token()).json().path("session").path("cash_out")
                .asString()).isEqualTo("75.00");
        assertThat(balance("CAIXA")).isEqualByComparingTo("0");
        // Balcão sem payments.reverse: negado.
        assertThat(api.postWith("/api/v1/payments/" + pay + "/reversals", balcao.token(), Map.of("note", "x"), key(2))
                .status()).isEqualTo(403);
    }

    @Test
    void escopoProprioEPrazoSoComAutorizacao() {
        String id = order(balcao, "1", null).path("id").asString();
        assertThat(api.get("/api/v1/orders/" + id, balcao2.token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/orders", balcao2.token()).json().path("items").size()).isZero();
        assertThat(api.get("/api/v1/orders/" + id, patrao.token()).status()).isEqualTo(200);
        // A prazo: balcão não autoriza; patrão autoriza com vencimento e libera a retirada.
        Map<String, Object> prazo = Map.of("method", "ON_ACCOUNT", "amount", "7.50", "due_date", "2026-12-15");
        assertThat(pay(balcao, id, key(), prazo).status()).isEqualTo(403);
        Api.Res ok = pay(patrao, id, key(), prazo);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().path("order").path("payment_state").asString()).isEqualTo("ON_ACCOUNT");
        assertThat(api.postWith("/api/v1/orders/" + id + "/fulfillments", balcao.token(), null, key()).status())
                .isEqualTo(200);
        assertThat(balance("CLIENTES")).isEqualByComparingTo("7.5"); // a receber do cliente
    }

    @Test
    void livroFinanceiroBalanceadoEImutavel() {
        String id = order(patrao, "2", null).path("id").asString();
        openCash(patrao, "10.00");
        pay(patrao, id, key(), cash("15.00", "20.00"));
        api.postWith("/api/v1/orders/" + id + "/fulfillments", patrao.token(), null, key());
        BigDecimal unbalanced = jdbc.sql("""
                        SELECT coalesce(sum(abs(d)), 0) FROM (SELECT sum(l.debit) - sum(l.credit) AS d FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id WHERE e.organization_id = :o GROUP BY e.id) x
                        """).param("o", org.id()).query(BigDecimal.class).single();
        assertThat(unbalanced).isEqualByComparingTo("0");
        try {
            jdbc.sql("UPDATE journal_lines SET debit = debit + 1 WHERE entry_id IN (SELECT id FROM journal_entries "
                    + "WHERE organization_id = :o)").param("o", org.id()).update();
            throw new AssertionError("livro aceitou alteração");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("somente inclusão");
        }
    }
}
