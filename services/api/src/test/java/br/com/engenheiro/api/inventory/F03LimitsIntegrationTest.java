package br.com.engenheiro.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/** Limites da F03 resolvidos: conta a pagar do recebimento, ajuste por contagem, unidades e anexos privados. */
@IntegrationTest
class F03LimitsIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person recebimento;
    Person recebimentoU2;
    Person balcao;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        recebimento = fx.person(org, org.branch1(), RoleTemplate.RECEBIMENTO, "Recebimento");
        recebimentoU2 = fx.person(org, org.branch2(), RoleTemplate.RECEBIMENTO, "Recebimento U2");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
    }

    private static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    private static Map<String, String> key(long version) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + version + "\"");
    }

    private String product(String sku) {
        Api.Res r = api.post("/api/v1/products", patrao.token(), Map.of("sku", sku, "name", "Cana " + sku,
                "base_unit", "DUZIA", "base_unit_confirmed", true), (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("id").asString();
    }

    private String supplier() {
        return api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Fornecedor Fictício"), (String) null)
                .json().path("id").asString();
    }

    private JsonNode draft(Person who, String sup, String productId, String qty, String cost) {
        Map<String, Object> line = new HashMap<>();
        line.put("product_id", productId);
        line.put("quantity_received", qty);
        line.put("unit_cost", cost);
        Api.Res r = api.postWith("/api/v1/inventory/receipts", who.token(), Map.of("supplier_id", sup,
                "document_number", "NF-77", "lines", List.of(line)), key());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    private JsonNode balance(String productId) {
        for (JsonNode b : api.get("/api/v1/inventory/balances?limit=100", patrao.token()).json().path("items")) {
            if (b.path("product_id").asString().equals(productId)) {
                return b;
            }
        }
        return null;
    }

    // ---------- conta a pagar ----------
    @Test
    void confirmacaoDoRecebimentoCriaUmaObrigacaoAPagarSemDuplicar() {
        String p = product("PG-1");
        JsonNode d = draft(patrao, supplier(), p, "10", "12.50");
        Map<String, String> k = key(1);
        Map<String, Object> body = Map.of("freight_amount", "5.00", "payment_due_date", "2026-12-10");
        Api.Res c1 = api.postWith("/api/v1/inventory/receipts/" + d.path("id").asString() + "/confirm", patrao.token(), body, k);
        Api.Res c2 = api.postWith("/api/v1/inventory/receipts/" + d.path("id").asString() + "/confirm", patrao.token(), body, k);
        assertThat(c1.status()).as(c1.body()).isEqualTo(200);
        assertThat(c2.status()).isEqualTo(200);

        JsonNode list = api.get("/api/v1/payables", patrao.token()).json().path("items");
        assertThat(list.size()).isEqualTo(1);
        JsonNode pay = list.get(0);
        assertThat(pay.path("amount").asString()).isEqualTo("130.00");
        assertThat(pay.path("balance").asString()).isEqualTo("130.00");
        assertThat(pay.path("due_date").asString()).isEqualTo("2026-12-10");
        assertThat(pay.path("state").asString()).isEqualTo("OPEN");
        assertThat(pay.path("source_id").asString()).isEqualTo(d.path("id").asString());
        // Sem recurso no contrato: só administrador; tentativa negada auditada.
        assertThat(api.get("/api/v1/payables", recebimento.token()).status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'access.denied' AND actor_user_id = :u "
                + "AND changes->>'capability' = 'payables.view'").param("u", recebimento.userId()).query(Integer.class)
                .single()).isEqualTo(1);
        // Data inválida recusa a confirmação inteira.
        JsonNode d2 = draft(patrao, supplier(), p, "1", "1");
        assertThat(api.postWith("/api/v1/inventory/receipts/" + d2.path("id").asString() + "/confirm", patrao.token(),
                Map.of("payment_due_date", "10/12/2026"), key(1)).status()).isEqualTo(422);
        assertThat(balance(p).path("physical").asString()).isEqualTo("10");
    }

    // ---------- ajuste por contagem ----------
    @Test
    void ajustePorContagemAplicaSoADiferencaComAprovacao() {
        String p = product("AJ-1");
        JsonNode d = draft(patrao, supplier(), p, "10", "10.00");
        api.postWith("/api/v1/inventory/receipts/" + d.path("id").asString() + "/confirm", patrao.token(), null, key(1));

        // Sobra: contou 12 com 10 no sistema.
        Api.Res a = api.postWith("/api/v1/inventory/adjustments", patrao.token(), Map.of("product_id", p,
                "counted_quantity", "12", "notes", "Contagem de sexta"), key());
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        assertThat(a.json().path("difference").asString()).isEqualTo("2");
        assertThat(balance(p).path("physical").asString()).isEqualTo("10"); // registro não altera saldo
        // Contagem igual ao sistema não gera ajuste.
        assertThat(api.postWith("/api/v1/inventory/adjustments", patrao.token(), Map.of("product_id", p,
                "counted_quantity", "10", "notes", "ok"), key()).status()).isEqualTo(422);
        // Movimento entre contagem e aprovação não é apagado: só a diferença (+2) entra.
        JsonNode d2 = draft(patrao, supplier(), p, "5", "10.00");
        api.postWith("/api/v1/inventory/receipts/" + d2.path("id").asString() + "/confirm", patrao.token(), null, key(1));
        Api.Res ap = api.postWith("/api/v1/inventory/adjustments/" + a.json().path("id").asString() + "/approve",
                patrao.token(), null, key(1));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(ap.json().path("total_cost").asString()).isEqualTo("20.00"); // 2 × médio 10
        JsonNode b = balance(p);
        assertThat(b.path("physical").asString()).isEqualTo("17");
        assertThat(b.path("value").asString()).isEqualTo("170.00");

        // Falta: contou 15 com 17.
        Api.Res f = api.postWith("/api/v1/inventory/adjustments", patrao.token(), Map.of("product_id", p,
                "counted_quantity", "15", "notes", "Recontagem"), key());
        Api.Res fa = api.postWith("/api/v1/inventory/adjustments/" + f.json().path("id").asString() + "/approve",
                patrao.token(), null, key(1));
        assertThat(fa.json().path("total_cost").asString()).isEqualTo("-20.00");
        assertThat(balance(p).path("physical").asString()).isEqualTo("15");
        assertThat(jdbc.sql("SELECT count(*) FROM stock_movements WHERE product_id = :p AND kind IN "
                + "('ADJUSTMENT_IN','ADJUSTMENT_OUT')").param("p", UUID.fromString(p)).query(Integer.class).single())
                .isEqualTo(2);
        // Já decidido: transição inválida.
        assertThat(api.postWith("/api/v1/inventory/adjustments/" + f.json().path("id").asString() + "/approve",
                patrao.token(), null, key(2)).status()).isEqualTo(409);
        // Sem capacidade de perdas/ajustes: 403.
        assertThat(api.postWith("/api/v1/inventory/adjustments", balcao.token(), Map.of("product_id", p,
                "counted_quantity", "1", "notes", "x"), key()).status()).isEqualTo(403);
    }

    @Test
    void sobraComEstoqueZeradoExigeCustoInformado() {
        String p = product("AJ-0");
        Api.Res semCusto = api.postWith("/api/v1/inventory/adjustments", patrao.token(), Map.of("product_id", p,
                "counted_quantity", "4", "notes", "Achado no depósito"), key());
        assertThat(api.postWith("/api/v1/inventory/adjustments/" + semCusto.json().path("id").asString() + "/approve",
                patrao.token(), null, key(1)).status()).isEqualTo(422);
        Api.Res comCusto = api.postWith("/api/v1/inventory/adjustments", patrao.token(), Map.of("product_id", p,
                "counted_quantity", "4", "unit_cost", "3.50", "notes", "Achado no depósito"), key());
        Api.Res ap = api.postWith("/api/v1/inventory/adjustments/" + comCusto.json().path("id").asString() + "/approve",
                patrao.token(), null, key(1));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(balance(p).path("value").asString()).isEqualTo("14.00");
    }

    // ---------- unidades ----------
    @Test
    void unidadesSoAdministradorCriaEDesativaComTrava() {
        Api.Res c = api.post("/api/v1/branches", patrao.token(), Map.of("name", "Unidade Feira"), (String) null);
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        assertThat(c.json().path("timezone").asString()).isEqualTo("America/Sao_Paulo");
        assertThat(api.post("/api/v1/branches", patrao.token(), Map.of("name", "Unidade Feira"), (String) null).status())
                .isEqualTo(422);
        assertThat(api.post("/api/v1/branches", patrao.token(), Map.of("name", "X", "timezone", "Lua/Base"),
                (String) null).status()).isEqualTo(422);
        assertThat(api.post("/api/v1/branches", balcao.token(), Map.of("name", "Minha"), (String) null).status())
                .isEqualTo(403);
        List<String> names = api.get("/api/v1/branches", balcao.token()).json().path("items").valueStream()
                .map(n -> n.path("name").asString()).toList();
        assertThat(names).contains("Matriz", "Unidade 2", "Unidade Feira");
        // Unidade com pessoas ativas não é desativada.
        assertThat(api.post("/api/v1/branches/" + org.branch2() + "/status", patrao.token(),
                Map.of("status", "INACTIVE", "reason", "fechou"), (String) null).status()).isEqualTo(409);
        Api.Res off = api.post("/api/v1/branches/" + c.json().path("id").asString() + "/status", patrao.token(),
                Map.of("status", "INACTIVE", "reason", "não abriu"), (String) null);
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(off.json().path("status").asString()).isEqualTo("INACTIVE");
    }

    // ---------- anexos ----------
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 'I', 'E', 'N', 'D',
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Test
    void anexoDoRecebimentoPrivadoValidadoEAuditado() {
        String p = product("AX-1");
        JsonNode d = draft(recebimento, supplier(), p, "3", null);
        String rid = d.path("id").asString();

        Api.Res ok = api.upload("/api/v1/inventory/receipts/" + rid + "/attachments", recebimento.token(),
                "romaneio.png", "image/png", PNG);
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        assertThat(ok.json().path("state").asString()).isEqualTo("AVAILABLE");
        assertThat(ok.json().path("mime").asString()).isEqualTo("image/png");
        assertThat(ok.json().path("scan_state").asString()).contains("ANTIVIRUS_NAO_CONFIGURADO");
        String docId = ok.json().path("id").asString();

        // Tipo declarado não conta: HTML disfarçado de PDF é recusado e não fica guardado.
        Api.Res html = api.upload("/api/v1/inventory/receipts/" + rid + "/attachments", recebimento.token(),
                "nota.pdf", "application/pdf", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertThat(html.json().path("state").asString()).isEqualTo("REJECTED");
        // PDF com JavaScript é recusado.
        Api.Res js = api.upload("/api/v1/inventory/receipts/" + rid + "/attachments", recebimento.token(), "x.pdf",
                "application/pdf", "%PDF-1.7\n1 0 obj << /OpenAction << /S /JavaScript /JS (app.alert(1)) >> >>\n%%EOF"
                        .getBytes(StandardCharsets.ISO_8859_1));
        assertThat(js.json().path("state").asString()).isEqualTo("REJECTED");
        assertThat(js.json().path("rejection").asString()).contains("conteúdo ativo");
        String rejected = js.json().path("id").asString();

        // Download autorizado pelo recebimento; bytes idênticos; sem cache e sem sniffing; auditado.
        Api.Res dl = api.getBytes("/api/v1/documents/" + docId + "/download", patrao.token());
        assertThat(dl.status()).isEqualTo(200);
        assertThat(dl.body().getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(PNG);
        assertThat(dl.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(dl.header("Cache-Control")).contains("no-store");
        assertThat(dl.header("Content-Disposition")).startsWith("attachment");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'documents.download' AND entity_id = :d")
                .param("d", docId).query(Integer.class).single()).isEqualTo(1);
        // Outra unidade: 404 (não revela existência). Recusado: não baixa.
        assertThat(api.getBytes("/api/v1/documents/" + docId + "/download", recebimentoU2.token()).status()).isEqualTo(404);
        assertThat(api.getBytes("/api/v1/documents/" + rejected + "/download", patrao.token()).status()).isEqualTo(409);
        // Quem não registra nem confere o recebimento não anexa.
        assertThat(api.upload("/api/v1/inventory/receipts/" + rid + "/attachments", recebimentoU2.token(), "a.png",
                "image/png", PNG).status()).isEqualTo(404);
        assertThat(api.upload("/api/v1/inventory/receipts/" + rid + "/attachments", balcao.token(), "a.png",
                "image/png", PNG).status()).isEqualTo(403);
        // Lista do recebimento.
        assertThat(api.get("/api/v1/inventory/receipts/" + rid + "/attachments", recebimento.token()).json()
                .path("items").size()).isEqualTo(3);
        // Arquivo acima do limite.
        assertThat(api.upload("/api/v1/inventory/receipts/" + rid + "/attachments", recebimento.token(), "g.png",
                "image/png", new byte[11 * 1024 * 1024]).status()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT sum(size_bytes) FROM documents WHERE owner_id = :r AND state = 'AVAILABLE'")
                .param("r", UUID.fromString(rid)).query(BigDecimal.class).single()).isEqualByComparingTo("20");
    }
}
