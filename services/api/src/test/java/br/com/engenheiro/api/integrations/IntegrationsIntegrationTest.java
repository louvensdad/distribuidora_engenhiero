package br.com.engenheiro.api.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.TestFiscalProvider;
import br.com.engenheiro.api.support.TestPaymentProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** F08 — INT-01..05: notas fiscais por adaptador, avisos de pagamento assinados e estados honestos sem provedor. */
@IntegrationTest
class IntegrationsIntegrationTest {
    static final String CNPJ = "11222333000181"; // fictício, dígitos válidos
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    InvoiceService invoiceService;
    Api api;
    Org org;
    Person patrao;
    Person contador;
    Person balcao;
    String product;
    String customer;

    @BeforeEach
    void setUp() {
        TestFiscalProvider.reset();
        TestPaymentProvider.reset();
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Contador");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
        for (String a : List.of("create", "confirm", "view")) {
            fx.grant(balcao, "payments", a, "BRANCH", null);
        }
        Api.Res p = api.post("/api/v1/products", patrao.token(), Map.of("sku", "CANA", "name", "Cana caiana",
                "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        product = p.json().path("id").asString();
        assertThat(api.post("/api/v1/products/" + product + "/prices", patrao.token(), Map.of("price", "7.50"),
                p.header("ETag")).status()).isEqualTo(200);
        String sup = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Sítio"), (String) null).json()
                .path("id").asString();
        JsonNode r = api.postWith("/api/v1/inventory/receipts", patrao.token(), Map.of("supplier_id", sup,
                "lines", List.of(Map.of("product_id", product, "quantity_received", "500", "unit_cost", "3.00"))), key()).json();
        api.postWith("/api/v1/inventory/receipts/" + r.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        customer = api.post("/api/v1/customers", patrao.token(), Map.of("name", "Mercado Exemplo",
                "personal_document", CNPJ), (String) null).json().path("id").asString();
    }

    @AfterEach
    void tearDown() {
        TestFiscalProvider.reset();
        TestPaymentProvider.reset();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long v) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + v + "\"");
    }

    String order(String qty) {
        Api.Res r = api.postWith("/api/v1/orders", patrao.token(), Map.of("type", "PICKUP", "customer_id", customer,
                "items", List.of(Map.of("product_id", product, "quantity", qty))), key());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("id").asString();
    }

    void configureFiscal() {
        long v = api.get("/api/v1/fiscal-settings", patrao.token()).json().path("version").asLong();
        Map<String, Object> s = new HashMap<>();
        s.put("document_type", "NFE");
        s.put("environment", "HOMOLOGACAO");
        s.put("series", 1);
        s.put("default_cfop", "5102");
        s.put("issuer_document", CNPJ);
        s.put("state_registration", "ISENTO");
        s.put("tax_regime", "SIMPLES_NACIONAL");
        Api.Res r = api.put("/api/v1/fiscal-settings", patrao.token(), s, "\"" + v + "\"");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(api.put("/api/v1/products/" + product + "/fiscal-data", contador.token(),
                Map.of("ncm", "1212.93.00", "fiscal_origin", 0), null).status()).isEqualTo(200);
    }

    /** Rascunho criado e conferido pelo contador, pronto para transmitir. */
    JsonNode readyInvoice(String qty) {
        Api.Res d = api.postWith("/api/v1/invoices", contador.token(), Map.of("order_id", order(qty)), key());
        assertThat(d.status()).as(d.body()).isEqualTo(201);
        Api.Res rv = api.post("/api/v1/invoices/" + d.json().path("id").asString() + "/review", contador.token(), Map.of(),
                "\"" + d.json().path("version").asLong() + "\"");
        assertThat(rv.status()).as(rv.body()).isEqualTo(200);
        assertThat(rv.json().path("state").asString()).isEqualTo("READY");
        return rv.json();
    }

    Api.Res transmit(JsonNode inv) {
        return api.postWith("/api/v1/invoices/" + inv.path("id").asString() + "/transmission", patrao.token(), null,
                key(inv.path("version").asLong()));
    }

    BigDecimal balance(String account) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND l.account = :a
                        """).param("o", org.id()).param("a", account).query(BigDecimal.class).single();
    }

    long attempts(String integration, String outcome) {
        return jdbc.sql("SELECT count(*) FROM integration_attempts WHERE organization_id = :o AND integration = :i AND outcome = :x")
                .param("o", org.id()).param("i", integration).param("x", outcome).query(Long.class).single();
    }

    // ---------- INT-01/INT-05: sem provedor, tudo BLOQUEADO e registrado; nada simulado ----------
    @Test
    void semProvedorTudoBloqueadoComHistoricoENadaSimulado() {
        JsonNode st = api.get("/api/v1/integrations", patrao.token()).json();
        for (JsonNode i : st.path("items")) {
            assertThat(i.path("state").asString()).as(i.path("integration").asString()).isEqualTo("NOT_CONFIGURED");
        }
        // Tipo fiscal não definido com o contador: nem rascunho.
        String o = order("10");
        Api.Res semTipo = api.postWith("/api/v1/invoices", contador.token(), Map.of("order_id", o), key());
        assertThat(semTipo.status()).isEqualTo(503);
        assertThat(semTipo.body()).contains("BLOQUEADA").contains("contador");
        assertThat(attempts("FISCAL", "BLOCKED")).isEqualTo(1);
        // Só o administrador define a configuração fiscal; contador não.
        assertThat(api.put("/api/v1/fiscal-settings", contador.token(), Map.of("document_type", "NFE"), "\"0\"").status())
                .isEqualTo(403);
        long v = api.get("/api/v1/fiscal-settings", patrao.token()).json().path("version").asLong();
        assertThat(api.put("/api/v1/fiscal-settings", patrao.token(), Map.of("document_type", "NFE",
                "issuer_document", "11222333000100"), "\"" + v + "\"").status()).isEqualTo(422); // CNPJ com dígito errado
        configureFiscal();
        assertThat(api.get("/api/v1/fiscal-settings", contador.token()).json().path("missing").size()).isZero();
        // Rascunho do contador: NCM retirado do produto → conferência recusa com o motivo.
        jdbc.sql("UPDATE products SET ncm = NULL WHERE id = CAST(:p AS uuid)").param("p", product).update();
        Api.Res d = api.postWith("/api/v1/invoices", contador.token(), Map.of("order_id", o), key());
        assertThat(d.status()).as(d.body()).isEqualTo(201);
        assertThat(d.json().path("state").asString()).isEqualTo("DRAFT");
        assertThat(d.json().path("total").asString()).isEqualTo("75.00");
        assertThat(d.json().path("document_type").asString()).isEqualTo("NFE");
        assertThat(d.json().path("provider_state").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(d.json().path("issues").toString()).contains("NCM");
        String id = d.json().path("id").asString();
        Api.Res semNcm = api.post("/api/v1/invoices/" + id + "/review", contador.token(), Map.of(), "\"1\"");
        assertThat(semNcm.status()).isEqualTo(422);
        assertThat(semNcm.body()).contains("NCM");
        // Segundo rascunho do mesmo pedido: recusado (uma nota viva por pedido).
        assertThat(api.postWith("/api/v1/invoices", contador.token(), Map.of("order_id", o), key()).status()).isEqualTo(409);
        api.put("/api/v1/products/" + product + "/fiscal-data", contador.token(), Map.of("ncm", "12129300"), null);
        JsonNode refreshed = api.get("/api/v1/invoices/" + id, contador.token()).json();
        assertThat(refreshed.path("lines").get(0).path("ncm").asString()).isEqualTo("12129300"); // rascunho já mostra
        assertThat(refreshed.path("issues").toString()).doesNotContain("NCM");
        Api.Res ok = api.post("/api/v1/invoices/" + id + "/review", contador.token(), Map.of(), "\"1\"");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().path("lines").get(0).path("ncm").asString()).isEqualTo("12129300");
        // Contador confere, mas não transmite (invoices.confirm é do patrão salvo concessão).
        assertThat(api.postWith("/api/v1/invoices/" + id + "/transmission", contador.token(), null, key(2)).status())
                .isEqualTo(403);
        Api.Res bloq = api.postWith("/api/v1/invoices/" + id + "/transmission", patrao.token(), null, key(2));
        assertThat(bloq.status()).isEqualTo(503);
        assertThat(bloq.body()).contains("NÃO CONFIGURADO").contains("Nada foi enviado");
        JsonNode after = api.get("/api/v1/invoices/" + id, patrao.token()).json();
        assertThat(after.path("state").asString()).isEqualTo("READY");
        assertThat(after.path("access_key").isNull()).isTrue();
        assertThat(after.path("history").toString()).contains("BLOCKED").contains("NÃO CONFIGURADO");
        assertThat(attempts("FISCAL", "BLOCKED")).isEqualTo(2);
        // Pagamentos: cobrança Pix BLOQUEADA; aviso para provedor inexistente não é aceito nem gravado.
        Api.Res pay = api.postWith("/api/v1/orders/" + order("4") + "/payments", patrao.token(),
                Map.of("parts", List.of(Map.of("method", "PIX", "amount", "30.00"))), key());
        assertThat(pay.status()).as(pay.body()).isEqualTo(200);
        String paymentId = pay.json().path("payments").get(0).path("id").asString();
        Api.Res ch = api.postWith("/api/v1/payments/" + paymentId + "/charge", patrao.token(), null, key());
        assertThat(ch.status()).isEqualTo(503);
        assertThat(ch.body()).contains("BLOQUEADA");
        byte[] body = "{\"id\":\"ev-1\",\"type\":\"PAYMENT_CONFIRMED\",\"txid\":\"x\",\"amount\":\"30.00\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(api.raw("/webhooks/payments/pix-teste", body, Map.of("Content-Type", "application/json",
                "X-Assinatura", TestPaymentProvider.sign(body))).status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_events").query(Long.class).single()).isZero();
        assertThat(api.get("/api/v1/integrations", balcao.token()).json().path("recent").size()).isZero(); // não admin
        assertThat(api.get("/api/v1/integrations", patrao.token()).json().path("recent").toString()).contains("BLOCKED");
    }

    // ---------- INT-02/INT-03: autorizada com XML/protocolo/chave; cancelamento separado e confirmado ----------
    @Test
    void autorizadaComXmlProtocoloEChaveECancelamentoSeparado() {
        configureFiscal();
        TestFiscalProvider.enabled = true;
        JsonNode inv = readyInvoice("8");
        Api.Res t = transmit(inv);
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        JsonNode a = t.json();
        assertThat(a.path("state").asString()).isEqualTo("AUTHORIZED");
        assertThat(a.path("access_key").asString()).matches("[0-9]{44}");
        assertThat(a.path("protocol").asString()).isNotBlank();
        assertThat(a.path("number").asLong()).isPositive();
        String xmlId = a.path("xml_document_id").asString();
        Api.Res xml = api.getBytes("/api/v1/documents/" + xmlId + "/download", patrao.token());
        assertThat(xml.status()).isEqualTo(200);
        assertThat(xml.body()).contains("<nfeProc>").contains(a.path("access_key").asString());
        assertThat(xml.header("Content-Disposition")).contains("attachment");
        assertThat(api.getBytes("/api/v1/documents/" + xmlId + "/download", contador.token()).status())
                .isEqualTo(403); // sem invoices.export
        // Repetir o mesmo comando (mesma chave) não reenvia.
        String id = a.path("id").asString();
        // Cancelamento: justificativa curta recusada; recusa da SEFAZ mantém autorizada; depois cancela.
        Api.Res curta = api.postWith("/api/v1/invoices/" + id + "/cancellation", patrao.token(), Map.of("reason", "erro"),
                key(a.path("version").asLong()));
        assertThat(curta.status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/invoices/" + id + "/cancellation", contador.token(),
                Map.of("reason", "Pedido devolvido pelo cliente"), key(a.path("version").asLong())).status()).isEqualTo(403);
        TestFiscalProvider.cancelMode = "REJECTED";
        Api.Res rec = api.postWith("/api/v1/invoices/" + id + "/cancellation", patrao.token(),
                Map.of("reason", "Pedido devolvido pelo cliente"), key(a.path("version").asLong()));
        assertThat(rec.status()).isEqualTo(409);
        assertThat(rec.body()).contains("continua autorizada");
        JsonNode still = api.get("/api/v1/invoices/" + id, patrao.token()).json();
        assertThat(still.path("state").asString()).isEqualTo("AUTHORIZED");
        TestFiscalProvider.cancelMode = "TIMEOUT";
        Api.Res pend = api.postWith("/api/v1/invoices/" + id + "/cancellation", patrao.token(),
                Map.of("reason", "Pedido devolvido pelo cliente"), key(still.path("version").asLong()));
        assertThat(pend.status()).isEqualTo(202);
        assertThat(pend.json().path("state").asString()).isEqualTo("CANCEL_PENDING");
        TestFiscalProvider.cancelMode = "CANCELLED";
        Api.Res c = api.postWith("/api/v1/invoices/" + id + "/cancellation", patrao.token(),
                Map.of("reason", "Pedido devolvido pelo cliente"), key(pend.json().path("version").asLong()));
        assertThat(c.status()).as(c.body()).isEqualTo(200);
        assertThat(c.json().path("state").asString()).isEqualTo("CANCELLED");
        assertThat(c.json().path("cancel_protocol").asString()).isNotBlank();
        assertThat(c.json().path("access_key").asString()).isEqualTo(a.path("access_key").asString());
        assertThat(TestFiscalProvider.EMISSIONS.get()).isEqualTo(1);
        // Rejeitada: estado próprio com motivo; pode descartar e refazer o rascunho do pedido.
        TestFiscalProvider.mode = TestFiscalProvider.Mode.REJECT;
        JsonNode inv2 = readyInvoice("2");
        Api.Res rj = transmit(inv2);
        assertThat(rj.json().path("state").asString()).isEqualTo("REJECTED");
        assertThat(rj.json().path("rejection_reason").asString()).contains("NCM");
        assertThat(rj.json().path("access_key").isNull()).isTrue();
        assertThat(rj.json().path("xml_document_id").isNull()).isTrue();
        assertThat(api.post("/api/v1/invoices/" + inv2.path("id").asString() + "/discard", contador.token(),
                Map.of("reason", "Corrigir cadastro"), "\"" + rj.json().path("version").asLong() + "\"").status()).isEqualTo(200);
        assertThat(api.postWith("/api/v1/invoices", contador.token(), Map.of("order_id", rj.json().path("order_id").asString()),
                key()).status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE organization_id = :o AND action IN "
                        + "('invoices.transmit', 'invoices.authorized', 'invoices.rejected', 'invoices.cancel', 'invoices.cancelled')")
                .param("o", org.id()).query(Long.class).single()).isGreaterThanOrEqualTo(5);
    }

    // ---------- INT-05: timeout após envio não vira nova emissão; incerto só é consultado ----------
    @Test
    void timeoutAposEnvioNaoViraEmissaoDuplicada() throws Exception {
        configureFiscal();
        TestFiscalProvider.enabled = true;
        TestFiscalProvider.mode = TestFiscalProvider.Mode.TIMEOUT_AFTER_ISSUE;
        JsonNode inv = readyInvoice("6");
        String id = inv.path("id").asString();
        Api.Res t = transmit(inv);
        assertThat(t.status()).isEqualTo(202);
        assertThat(t.json().path("state").asString()).isEqualTo("UNCERTAIN");
        assertThat(t.json().path("access_key").isNull()).isTrue();
        // Nova tentativa de transmitir (outra chave): recusada — incerto não se reenvia.
        TestFiscalProvider.mode = TestFiscalProvider.Mode.AUTHORIZE;
        Api.Res again = transmit(t.json());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body()).contains("incerto");
        assertThat(TestFiscalProvider.SUBMITS.get()).isEqualTo(1);
        // Consulta: o provedor tinha emitido → autorizada com a MESMA nota.
        Api.Res q = api.post("/api/v1/invoices/" + id + "/resolution", patrao.token(), Map.of(), null);
        assertThat(q.status()).as(q.body()).isEqualTo(200);
        assertThat(q.json().path("state").asString()).isEqualTo("AUTHORIZED");
        assertThat(TestFiscalProvider.EMISSIONS.get()).isEqualTo(1);
        assertThat(TestFiscalProvider.SUBMITS.get()).isEqualTo(1);
        assertThat(q.json().path("history").toString()).contains("TIMEOUT").contains("SUCCESS");

        // Timeout sem o provedor ter recebido: o job consulta, volta a conferida, e só então reenvia (mesma referência).
        TestFiscalProvider.mode = TestFiscalProvider.Mode.TIMEOUT_NOT_RECEIVED;
        JsonNode inv2 = readyInvoice("3");
        assertThat(transmit(inv2).json().path("state").asString()).isEqualTo("UNCERTAIN");
        jdbc.sql("UPDATE invoices SET transmitted_at = now() - interval '5 minutes' WHERE id = CAST(:id AS uuid)")
                .param("id", inv2.path("id").asString()).update();
        assertThat(invoiceService.resolvePending()).isEqualTo(1);
        JsonNode back = api.get("/api/v1/invoices/" + inv2.path("id").asString(), patrao.token()).json();
        assertThat(back.path("state").asString()).isEqualTo("READY");
        TestFiscalProvider.mode = TestFiscalProvider.Mode.AUTHORIZE;
        assertThat(transmit(back).json().path("state").asString()).isEqualTo("AUTHORIZED");
        assertThat(TestFiscalProvider.EMISSIONS.get()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'invoices.resolve' AND actor_kind = 'SERVICE' "
                + "AND organization_id = :o").param("o", org.id()).query(Long.class).single()).isEqualTo(1);

        // Provedor fora do ar (nada saiu): volta a conferida, sem estado incerto.
        TestFiscalProvider.mode = TestFiscalProvider.Mode.UNAVAILABLE;
        JsonNode inv3 = readyInvoice("1");
        Api.Res down = transmit(inv3);
        assertThat(down.status()).isEqualTo(503);
        assertThat(api.get("/api/v1/invoices/" + inv3.path("id").asString(), patrao.token()).json().path("state").asString())
                .isEqualTo("READY");

        // Corrida: dois cliques simultâneos em transmitir → um só envio ao provedor.
        TestFiscalProvider.mode = TestFiscalProvider.Mode.AUTHORIZE;
        JsonNode inv4 = readyInvoice("5");
        int before = TestFiscalProvider.SUBMITS.get();
        TestFiscalProvider.gate = new java.util.concurrent.CyclicBarrier(4); // os 4 leem "conferida" antes de gravar
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> transmit(inv4).status());
        }
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            codes.add(f.get());
        }
        pool.shutdown();
        TestFiscalProvider.gate = null;
        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).allMatch(c -> c == 200 || c == 409 || c == 412);
        assertThat(TestFiscalProvider.SUBMITS.get() - before).isEqualTo(1);
    }

    // ---------- INT-04: aviso assinado e idempotente; assinatura inválida ou reenvio não mexe no caixa ----------
    @Test
    void webhookAssinadoIdempotenteEInvalidoNaoAlteraCaixa() throws Exception {
        TestPaymentProvider.enabled = true;
        Api.Res pay = api.postWith("/api/v1/orders/" + order("4") + "/payments", patrao.token(),
                Map.of("parts", List.of(Map.of("method", "PIX", "amount", "30.00"))), key());
        assertThat(pay.status()).as(pay.body()).isEqualTo(200);
        String paymentId = pay.json().path("payments").get(0).path("id").asString();
        Api.Res ch = api.postWith("/api/v1/payments/" + paymentId + "/charge", patrao.token(), null, key());
        assertThat(ch.status()).as(ch.body()).isEqualTo(200);
        String txid = ch.json().path("provider_reference").asString();
        assertThat(ch.json().path("code").asString()).startsWith("000201");
        // Repetir a cobrança devolve a mesma (não cria outra).
        assertThat(api.postWith("/api/v1/payments/" + paymentId + "/charge", patrao.token(), null, key()).json()
                .path("provider_reference").asString()).isEqualTo(txid);
        BigDecimal banco0 = balance("BANCO");

        byte[] ok = ("{\"id\":\"ev-100\",\"type\":\"PAYMENT_CONFIRMED\",\"txid\":\"" + txid + "\",\"amount\":\"30.00\"}")
                .getBytes(StandardCharsets.UTF_8);
        // Assinatura inválida (ou ausente, ou corpo alterado depois de assinar): 401, nada muda.
        String good = TestPaymentProvider.sign(ok);
        String bad = (good.charAt(0) == '0' ? "1" : "0") + good.substring(1); // sempre difere da assinatura certa
        assertThat(hook(ok, bad).status()).isEqualTo(401);
        assertThat(hook(ok, null).status()).isEqualTo(401);
        byte[] tampered = new String(ok, StandardCharsets.UTF_8).replace("30.00", "300.00").getBytes(StandardCharsets.UTF_8);
        assertThat(hook(tampered, TestPaymentProvider.sign(ok)).status()).isEqualTo(401);
        assertThat(balance("BANCO")).isEqualByComparingTo(banco0);
        assertThat(paymentState(paymentId)).isEqualTo("PENDING");
        // Valor diferente do cobrado: triagem, sem recebimento.
        byte[] wrong = ("{\"id\":\"ev-099\",\"type\":\"PAYMENT_CONFIRMED\",\"txid\":\"" + txid + "\",\"amount\":\"3.00\"}")
                .getBytes(StandardCharsets.UTF_8);
        Api.Res w = hook(wrong, TestPaymentProvider.sign(wrong));
        assertThat(w.status()).isEqualTo(200);
        assertThat(w.json().path("status").asString()).isEqualTo("UNMATCHED");
        assertThat(paymentState(paymentId)).isEqualTo("PENDING");
        // Referência desconhecida: triagem.
        byte[] unknown = "{\"id\":\"ev-098\",\"type\":\"PAYMENT_CONFIRMED\",\"txid\":\"nao-existe\",\"amount\":\"30.00\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(hook(unknown, TestPaymentProvider.sign(unknown)).json().path("status").asString()).isEqualTo("UNMATCHED");
        // Válido: confirma uma vez, lançamento BANCO a débito.
        Api.Res first = hook(ok, TestPaymentProvider.sign(ok));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().path("status").asString()).isEqualTo("PROCESSED");
        assertThat(paymentState(paymentId)).isEqualTo("CONFIRMED");
        assertThat(balance("BANCO").subtract(banco0)).isEqualByComparingTo("30.00");
        // Reenvio do mesmo evento: DUPLICATE, caixa igual.
        Api.Res dup = hook(ok, TestPaymentProvider.sign(ok));
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.json().path("status").asString()).isEqualTo("DUPLICATE");
        assertThat(balance("BANCO").subtract(banco0)).isEqualByComparingTo("30.00");
        // Estorno avisado 5 vezes ao mesmo tempo: um só estorno.
        byte[] refund = ("{\"id\":\"ev-200\",\"type\":\"PAYMENT_REFUNDED\",\"txid\":\"" + txid + "\",\"amount\":\"30.00\"}")
                .getBytes(StandardCharsets.UTF_8);
        String sig = TestPaymentProvider.sign(refund);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> hook(refund, sig).json().path("status").asString());
        }
        List<String> statuses = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).filteredOn("PROCESSED"::equals).hasSize(1);
        assertThat(statuses).filteredOn(s -> !s.equals("PROCESSED")).allMatch("DUPLICATE"::equals);
        assertThat(paymentState(paymentId)).isEqualTo("REVERSED");
        assertThat(balance("BANCO")).isEqualByComparingTo(banco0);
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE source_type = 'payment' AND source_id = CAST(:p AS uuid)")
                .param("p", paymentId).query(Long.class).single()).isEqualTo(2);
        // Autoria: identidade técnica (SERVICE), nunca uma pessoa.
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE entity_id = :p AND actor_kind = 'SERVICE' "
                + "AND action IN ('payments.confirm', 'payments.reverse')").param("p", paymentId).query(Long.class).single())
                .isEqualTo(2);
        // Triagem para o administrador; balcão não vê.
        JsonNode events = api.get("/api/v1/integrations/webhook-events", patrao.token()).json().path("items");
        assertThat(events.toString()).contains("REJECTED_SIGNATURE").contains("UNMATCHED").contains("DUPLICATE")
                .doesNotContain(TestPaymentProvider.SECRET);
        assertThat(api.get("/api/v1/integrations/webhook-events", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/integrations", patrao.token()).json().path("items").get(1).path("state").asString())
                .isEqualTo("CONFIGURED");
    }

    Api.Res hook(byte[] body, String signature) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        if (signature != null) {
            h.put("X-Assinatura", signature);
        }
        return api.raw("/webhooks/payments/pix-teste", body, h);
    }

    String paymentState(String id) {
        return jdbc.sql("SELECT state FROM payments WHERE id = CAST(:id AS uuid)").param("id", id).query(String.class).single();
    }
}
