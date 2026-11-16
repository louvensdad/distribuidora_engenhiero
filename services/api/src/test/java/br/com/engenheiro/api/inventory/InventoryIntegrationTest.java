package br.com.engenheiro.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import br.com.engenheiro.api.inventory.StockLedger.Source;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** F03: produtos/medidas, fornecedores, recebimento com custo e frete, saldo, reservas, perdas e devoluções. */
@IntegrationTest
class InventoryIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StockLedger ledger;
    @Autowired
    TransactionTemplate tx;
    Api api;
    Org org;
    Person patrao;
    Person recebimento;
    Person recebimentoUnidade2;
    Person balcao;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        recebimento = fx.person(org, org.branch1(), RoleTemplate.RECEBIMENTO, "Recebimento");
        recebimentoUnidade2 = fx.person(org, org.branch2(), RoleTemplate.RECEBIMENTO, "Recebimento U2");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
    }

    // ---------- auxiliares ----------
    private static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    private static Map<String, String> key(String k, long version) {
        return Map.of("Idempotency-Key", k, "If-Match", "\"" + version + "\"");
    }

    private String product(String sku, String baseUnit, boolean confirmed, List<Map<String, String>> conv) {
        Map<String, Object> body = new HashMap<>();
        body.put("sku", sku);
        body.put("name", "Cana " + sku);
        body.put("base_unit", baseUnit);
        body.put("base_unit_confirmed", confirmed);
        body.put("conversions", conv);
        Api.Res r = api.post("/api/v1/products", patrao.token(), body, (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("id").asString();
    }

    private String supplier() {
        Api.Res r = api.post("/api/v1/suppliers", patrao.token(), Map.of("name", "Fornecedor Fictício",
                "contact", "(11) 90000-1111"), (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json().path("id").asString();
    }

    private static Map<String, Object> line(String productId, String unit, String expected, String received,
                                            String unitCost) {
        Map<String, Object> l = new HashMap<>();
        l.put("product_id", productId);
        l.put("unit", unit);
        l.put("quantity_expected", expected);
        l.put("quantity_received", received);
        l.put("unit_cost", unitCost);
        return l;
    }

    private Api.Res draft(Person who, String supplierId, String freight, Map<String, Object>... lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplier_id", supplierId);
        body.put("document_number", "NF-0001");
        body.put("freight_amount", freight);
        body.put("lines", List.of(lines));
        return api.postWith("/api/v1/inventory/receipts", who.token(), body, key());
    }

    private JsonNode confirm(String receiptId, long version, Map<String, Object> body) {
        Api.Res r = api.postWith("/api/v1/inventory/receipts/" + receiptId + "/confirm", patrao.token(), body,
                key("conf-" + UUID.randomUUID(), version));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    /** Recebe e confirma pelo patrão (custo visível). */
    private JsonNode receiveConfirmed(String productId, String qty, String unitCost, String freight) {
        Api.Res d = draft(patrao, supplier(), freight, line(productId, null, qty, qty, unitCost));
        assertThat(d.status()).as(d.body()).isEqualTo(201);
        return confirm(d.json().path("id").asString(), d.json().path("version").asLong(), null);
    }

    private JsonNode balance(Person who, String productId) {
        for (JsonNode b : api.get("/api/v1/inventory/balances?limit=100", who.token()).json().path("items")) {
            if (b.path("product_id").asString().equals(productId)) {
                return b;
            }
        }
        return null;
    }

    private BigDecimal sumMovements(String productId, String kind) {
        return jdbc.sql("SELECT coalesce(sum(total_cost), 0) FROM stock_movements WHERE product_id = :p AND kind = :k")
                .param("p", UUID.fromString(productId)).param("k", kind).query(BigDecimal.class).single();
    }

    private Actor actor(Person p, UUID branch) {
        return new Actor(p.userId(), "teste", p.membershipId(), 1, org.id(), "org", branch, "b", Sector.RETIRADA,
                "ADMIN", true, "web", List.of(), Map.of(), Instant.now());
    }

    // ---------- INV-01 ----------
    @Test
    void produtoComConversaoPorProdutoEUnidadeConfirmada() {
        String semConfirmar = product("CANA-SC", "DUZIA", false, List.of());
        Api.Res bloqueado = draft(patrao, supplier(), null, line(semConfirmar, null, "10", "10", "5.00"));
        assertThat(bloqueado.status()).isEqualTo(422);
        assertThat(bloqueado.body()).contains("Unidade comercial do produto ainda não confirmada");

        Api.Res p = api.get("/api/v1/products/" + semConfirmar, patrao.token());
        Api.Res conf = api.postWith("/api/v1/products/" + semConfirmar + "/unit-confirmation", patrao.token(), null,
                Map.of("If-Match", p.header("ETag")));
        assertThat(conf.status()).as(conf.body()).isEqualTo(200);
        assertThat(conf.json().path("base_unit_confirmed").asBoolean()).isTrue();

        // Fator por produto: feixe deste produto = 3 dúzias; sem equivalência universal.
        String id = product("CANA-FX", "DUZIA", true, List.of(Map.of("unit", "FEIXE", "factor", "3")));
        Api.Res d = draft(patrao, supplier(), null, line(id, "FEIXE", "10", "10", "30.00"));
        assertThat(d.status()).as(d.body()).isEqualTo(201);
        assertThat(d.json().path("lines").get(0).path("base_quantity").asString()).isEqualTo("30");
        // Medida sem conversão cadastrada é recusada.
        assertThat(draft(patrao, supplier(), null, line(id, "KG", "1", "1", "1")).status()).isEqualTo(422);

        // Nova vigência não altera a linha já gravada (fator guardado na linha).
        Api.Res cur = api.get("/api/v1/products/" + id, patrao.token());
        Api.Res nova = api.post("/api/v1/products/" + id + "/conversions", patrao.token(),
                Map.of("unit", "FEIXE", "factor", "2.5"), cur.header("ETag"));
        assertThat(nova.status()).as(nova.body()).isEqualTo(200);
        assertThat(nova.json().path("conversions").get(0).path("factor").asString()).isEqualTo("2.5");
        Api.Res antiga = api.get("/api/v1/inventory/receipts/" + d.json().path("id").asString(), patrao.token());
        assertThat(antiga.json().path("lines").get(0).path("factor").asString()).isEqualTo("3");
        assertThat(jdbc.sql("SELECT count(*) FROM unit_conversions WHERE product_id = :p")
                .param("p", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(2);
        // Versão antiga do produto: 412.
        assertThat(api.post("/api/v1/products/" + id + "/conversions", patrao.token(),
                Map.of("unit", "FEIXE", "factor", "4"), cur.header("ETag")).status()).isEqualTo(412);
        // Balcão só consulta catálogo.
        assertThat(api.post("/api/v1/products", balcao.token(), Map.of("sku", "X", "name", "X", "base_unit", "DUZIA"),
                (String) null).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/products/" + id, balcao.token()).status()).isEqualTo(200);
    }

    // ---------- INV-02 ----------
    @Test
    void recebimentoQuantitativoSemCustoVisivelEConferenciaComCustoEFrete() {
        String p1 = product("CANA-A", "DUZIA", true, List.of());
        String p2 = product("CANA-B", "DUZIA", true, List.of());
        String sup = supplier();

        // Recebimento não vê custo: informar custo é recusado.
        Api.Res comCusto = draft(recebimento, sup, null, line(p1, null, "100", "100", "10.00"));
        assertThat(comCusto.status()).isEqualTo(422);
        assertThat(comCusto.body()).contains("unit_cost");
        Api.Res comFrete = draft(recebimento, sup, "50.00", line(p1, null, "100", "100", null));
        assertThat(comFrete.status()).isEqualTo(422);

        // Rascunho só com quantidades; entrega incompleta registra diferença.
        Api.Res d = draft(recebimento, sup, null, line(p1, null, "100", "98", null), line(p2, null, "50", "50", null));
        assertThat(d.status()).as(d.body()).isEqualTo(201);
        JsonNode r = d.json();
        assertThat(r.path("status").asString()).isEqualTo("DRAFT");
        assertThat(r.path("lines").get(0).path("difference").asString()).isEqualTo("-2");
        assertThat(r.path("masked_fields").toString()).contains("lines.unit_cost");
        String id = r.path("id").asString();
        // Rascunho não mexe no estoque.
        assertThat(balance(patrao, p1)).isNull();
        // Fornecedor: contato mascarado para o recebimento.
        Api.Res s = api.get("/api/v1/suppliers/" + sup, recebimento.token());
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.json().path("contact").isNull()).isTrue();
        // Confirmar exige capacidade concedida pelo dono.
        assertThat(api.postWith("/api/v1/inventory/receipts/" + id + "/confirm", recebimento.token(), null,
                key("conf-sem-permissao-1", 1)).status()).isEqualTo(403);
        // Outra unidade não enxerga o recebimento por ID.
        assertThat(api.get("/api/v1/inventory/receipts/" + id, recebimentoUnidade2.token()).status()).isEqualTo(404);
        // Sem custo, a conferência não confirma (não inventa custo).
        Api.Res semCusto = api.postWith("/api/v1/inventory/receipts/" + id + "/confirm", patrao.token(), null,
                key("conf-sem-custo-01", 1));
        assertThat(semCusto.status()).isEqualTo(422);
        assertThat(semCusto.body()).contains("Custo unitário pendente");

        // Patrão confere com custo e frete. Frete 30.00 rateado pelo valor 980/500 → 19.86 e o resto (10.14) na última linha.
        JsonNode c = confirm(id, 1, Map.of("freight_amount", "30.00", "lines",
                List.of(Map.of("line_no", 1, "unit_cost", "10.00"), Map.of("line_no", 2, "unit_cost", "10.00"))));
        assertThat(c.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(c.path("total_cost").asString()).isEqualTo("1510.00");
        assertThat(c.path("lines").get(0).path("freight_share").asString()).isEqualTo("19.86");
        assertThat(c.path("lines").get(1).path("freight_share").asString()).isEqualTo("10.14");
        assertThat(c.path("confirmed_by_name").asString()).isEqualTo("Patrão");

        JsonNode b = balance(patrao, p1);
        assertThat(b.path("physical").asString()).isEqualTo("98");
        assertThat(b.path("value").asString()).isEqualTo("999.86");
        // Recebimento vê quantidade, não valor.
        JsonNode bq = balance(recebimento, p1);
        assertThat(bq.path("physical").asString()).isEqualTo("98");
        assertThat(bq.path("value").isNull()).isTrue();
        assertThat(bq.path("average_cost").isNull()).isTrue();
        Api.Res vista = api.get("/api/v1/inventory/receipts/" + id, recebimento.token());
        assertThat(vista.json().path("total_cost").isNull()).isTrue();
        assertThat(vista.body()).doesNotContain("1510", "19.86");

        // Confirmado não é reconfirmado.
        assertThat(api.postWith("/api/v1/inventory/receipts/" + id + "/confirm", patrao.token(), null,
                key("conf-de-novo-0001", c.path("version").asLong())).status()).isEqualTo(409);
        // Auditoria sem valores de custo.
        String changes = jdbc.sql("SELECT CAST(changes AS text) FROM audit_events WHERE action = "
                + "'inventory.receipts.confirm' AND entity_id = :id").param("id", id).query(String.class).single();
        assertThat(changes).doesNotContain("1510", "19.86");
    }

    @Test
    void idempotenciaDoRecebimento() {
        String p = product("CANA-ID", "DUZIA", true, List.of());
        String sup = supplier();
        Map<String, Object> body = new HashMap<>();
        body.put("supplier_id", sup);
        body.put("lines", List.of(line(p, null, null, "5", null)));
        Map<String, String> k = Map.of("Idempotency-Key", "recebimento-idem-0001");
        Api.Res a = api.postWith("/api/v1/inventory/receipts", recebimento.token(), body, k);
        Api.Res b = api.postWith("/api/v1/inventory/receipts", recebimento.token(), body, k);
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(201);
        assertThat(b.json().path("id").asString()).isEqualTo(a.json().path("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_receipts WHERE supplier_id = :s")
                .param("s", UUID.fromString(sup)).query(Integer.class).single()).isEqualTo(1);

        body.put("lines", List.of(line(p, null, null, "6", null)));
        Api.Res c = api.postWith("/api/v1/inventory/receipts", recebimento.token(), body, k);
        assertThat(c.status()).isEqualTo(409);
        assertThat(c.body()).contains("IDEMPOTENCY_CONFLICT");
        assertThat(api.postWith("/api/v1/inventory/receipts", recebimento.token(), body, Map.of()).status())
                .isEqualTo(422);

        // Confirmação repetida com a mesma chave não entra duas vezes no estoque.
        String id = a.json().path("id").asString();
        Map<String, Object> cbody = Map.of("lines", List.of(Map.of("line_no", 1, "unit_cost", "2.00")));
        Map<String, String> ck = key("confirmacao-idem-01", 1);
        Api.Res c1 = api.postWith("/api/v1/inventory/receipts/" + id + "/confirm", patrao.token(), cbody, ck);
        Api.Res c2 = api.postWith("/api/v1/inventory/receipts/" + id + "/confirm", patrao.token(), cbody, ck);
        assertThat(c1.status()).isEqualTo(200);
        assertThat(c2.status()).isEqualTo(200);
        assertThat(balance(patrao, p).path("physical").asString()).isEqualTo("5");
        assertThat(jdbc.sql("SELECT count(*) FROM stock_movements WHERE product_id = :p")
                .param("p", UUID.fromString(p)).query(Integer.class).single()).isEqualTo(1);
    }

    // ---------- INV-04: custo médio e CMV ----------
    @Test
    void recebimentoDe100EVendaDe20NaoLancaAs100ComoCmv() {
        String p = product("CANA-CMV", "DUZIA", true, List.of());
        receiveConfirmed(p, "100", "10.00", "50.00"); // valor 1050, médio 10.50
        receiveConfirmed(p, "50", "12.00", null);      // valor 1650 / 150 = 11.00
        JsonNode b = balance(patrao, p);
        assertThat(b.path("value").asString()).isEqualTo("1650.00");
        assertThat(b.path("average_cost").asString()).isEqualTo("11");

        Actor a = actor(patrao, org.branch1());
        UUID order = Ids.newId();
        tx.executeWithoutResult(s -> {
            UUID res = ledger.reserve(a, org.branch1(), UUID.fromString(p), new BigDecimal("20"), "order_line", order,
                    null);
            ledger.fulfillReservation(a, res, new Source("order_line", order, 1));
        });
        // CMV = só as 20 dúzias vendidas, ao custo médio; o restante continua estoque.
        assertThat(sumMovements(p, "SALE_ISSUE").negate()).isEqualByComparingTo("220.00");
        JsonNode after = balance(patrao, p);
        assertThat(after.path("physical").asString()).isEqualTo("130");
        assertThat(after.path("reserved").asString()).isEqualTo("0");
        assertThat(after.path("value").asString()).isEqualTo("1430.00");
        // Compra (entradas) não é custo do período: entradas 1650, CMV 220.
        assertThat(sumMovements(p, "RECEIPT")).isEqualByComparingTo("1650");
    }

    // ---------- INV-03: concorrência ----------
    @RepeatedTest(3)
    void duasReservasConcorrentesNaoNegativamEstoque() throws Exception {
        String p = product("CANA-RS-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "DUZIA", true,
                List.of());
        receiveConfirmed(p, "10", "1.00", null);
        Actor a = actor(patrao, org.branch1());
        UUID pid = UUID.fromString(p);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(CompletableFuture.supplyAsync(() -> attemptReserve(start, a, pid, "6"), pool));
        }
        start.countDown();
        List<String> out = results.stream().map(CompletableFuture::join).toList();
        assertThat(out).containsExactlyInAnyOrder("OK", ErrorCode.STOCK_INSUFFICIENT.name());

        // 20 pedidos de 1 dúzia disputando 4 disponíveis: exatamente 4 passam.
        CountDownLatch start2 = new CountDownLatch(1);
        List<CompletableFuture<String>> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(CompletableFuture.supplyAsync(() -> attemptReserve(start2, a, pid, "1"), pool));
        }
        start2.countDown();
        long ok = many.stream().map(CompletableFuture::join).filter("OK"::equals).count();
        pool.shutdown();
        assertThat(ok).isEqualTo(4);
        JsonNode b = balance(patrao, p);
        assertThat(b.path("reserved").asString()).isEqualTo("10");
        assertThat(b.path("available").asString()).isEqualTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM stock_reservations WHERE product_id = :p AND state = 'ACTIVE'")
                .param("p", pid).query(Integer.class).single()).isEqualTo(5);
    }

    private String attemptReserve(CountDownLatch start, Actor a, UUID pid, String qty) {
        try {
            start.await();
            tx.executeWithoutResult(s -> ledger.reserve(a, org.branch1(), pid, new BigDecimal(qty), "order_line",
                    Ids.newId(), null));
            return "OK";
        } catch (ApiException e) {
            return e.code().name();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reservaRepetidaDaMesmaOperacaoNaoDuplicaELiberacaoDevolveDisponivel() {
        String p = product("CANA-RL", "DUZIA", true, List.of());
        receiveConfirmed(p, "10", "1.00", null);
        Actor a = actor(patrao, org.branch1());
        UUID op = Ids.newId();
        UUID r1 = tx.execute(s -> ledger.reserve(a, org.branch1(), UUID.fromString(p), new BigDecimal("3"),
                "order_line", op, Instant.now().plusSeconds(3600)));
        UUID r2 = tx.execute(s -> ledger.reserve(a, org.branch1(), UUID.fromString(p), new BigDecimal("3"),
                "order_line", op, null));
        assertThat(r2).isEqualTo(r1);
        assertThat(balance(patrao, p).path("reserved").asString()).isEqualTo("3");
        assertThat(Boolean.TRUE.equals(tx.execute(s -> ledger.release(r1, "RELEASED")))).isTrue();
        assertThat(Boolean.TRUE.equals(tx.execute(s -> ledger.release(r1, "RELEASED")))).isFalse();
        assertThat(balance(patrao, p).path("available").asString()).isEqualTo("10");
    }

    // ---------- INV-05: perdas ----------
    @Test
    void perdaBaixaFisicoUmaVezPeloCustoMedioEForaDoCmv() {
        String p = product("CANA-PD", "DUZIA", true, List.of(Map.of("unit", "UNIDADE", "factor", "0.083333")));
        receiveConfirmed(p, "100", "12.00", null); // médio 12.00
        // Quem não tem a capacidade não registra perda.
        assertThat(api.postWith("/api/v1/inventory/losses", balcao.token(), Map.of("product_id", p, "quantity", "1",
                "reason", "QUEBRA"), key()).status()).isEqualTo(403);
        // Motivo fora da lista é recusado.
        assertThat(api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", p, "quantity", "1",
                "reason", "SUMIU"), key()).status()).isEqualTo(422);

        Api.Res l = api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", p, "quantity", "5",
                "reason", "DETERIORACAO", "notes", "Cana passou do ponto"), key());
        assertThat(l.status()).as(l.body()).isEqualTo(201);
        assertThat(l.json().path("status").asString()).isEqualTo("PENDING");
        assertThat(balance(patrao, p).path("physical").asString()).isEqualTo("100"); // registro não baixa
        String id = l.json().path("id").asString();

        Map<String, String> k = key("aprovar-perda-0001", 1);
        Api.Res ap = api.postWith("/api/v1/inventory/losses/" + id + "/approve", patrao.token(), null, k);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(ap.json().path("status").asString()).isEqualTo("APPROVED");
        assertThat(ap.json().path("total_cost").asString()).isEqualTo("60.00");
        // Repetição com a mesma chave devolve o mesmo resultado; outra chave é transição inválida.
        Api.Res again = api.postWith("/api/v1/inventory/losses/" + id + "/approve", patrao.token(), null, k);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().path("movement_id").asString()).isEqualTo(ap.json().path("movement_id").asString());
        assertThat(api.postWith("/api/v1/inventory/losses/" + id + "/approve", patrao.token(), null,
                key("aprovar-perda-0002", 2)).status()).isEqualTo(409);

        JsonNode b = balance(patrao, p);
        assertThat(b.path("physical").asString()).isEqualTo("95");
        assertThat(b.path("value").asString()).isEqualTo("1140.00");
        assertThat(sumMovements(p, "LOSS")).isEqualByComparingTo("-60");
        assertThat(sumMovements(p, "SALE_ISSUE")).isEqualByComparingTo("0"); // perda não entra no CMV
        assertThat(jdbc.sql("SELECT count(*) FROM stock_movements WHERE kind = 'LOSS' AND source_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);
        // Autor e aprovador gravados.
        JsonNode view = api.get("/api/v1/inventory/losses/" + id, patrao.token()).json();
        assertThat(view.path("created_by_name").asString()).isEqualTo("Patrão");
        assertThat(view.path("decided_by_name").asString()).isEqualTo("Patrão");

        // Perda maior que o disponível (reservado protegido) é recusada.
        Actor a = actor(patrao, org.branch1());
        tx.executeWithoutResult(s -> ledger.reserve(a, org.branch1(), UUID.fromString(p), new BigDecimal("90"),
                "order_line", Ids.newId(), null));
        Api.Res big = api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", p, "quantity", "10",
                "reason", "QUEBRA"), key());
        Api.Res bigAp = api.postWith("/api/v1/inventory/losses/" + big.json().path("id").asString() + "/approve",
                patrao.token(), null, key("aprovar-perda-0003", 1));
        assertThat(bigAp.status()).isEqualTo(409);
        assertThat(bigAp.body()).contains("STOCK_INSUFFICIENT");
        assertThat(balance(patrao, p).path("physical").asString()).isEqualTo("95");
    }

    // ---------- INV-06: devolução e estorno vinculados ----------
    @Test
    void devolucaoAoFornecedorEEstornoDePerdaUsamCustoHistoricoEVinculo() {
        String p = product("CANA-DV", "DUZIA", true, List.of());
        JsonNode r1 = receiveConfirmed(p, "10", "10.00", "10.00"); // custo histórico 11.00
        receiveConfirmed(p, "10", "20.00", null);                   // médio (110+200)/20 = 15.50
        String rid = r1.path("id").asString();

        Api.Res over = api.postWith("/api/v1/inventory/receipts/" + rid + "/returns", patrao.token(),
                Map.of("line_no", 1, "quantity", "11", "reason", "Mercadoria verde"), key());
        assertThat(over.status()).isEqualTo(422);
        assertThat(api.postWith("/api/v1/inventory/receipts/" + rid + "/returns", recebimento.token(),
                Map.of("line_no", 1, "quantity", "1", "reason", "x"), key()).status()).isEqualTo(403);

        Api.Res ret = api.postWith("/api/v1/inventory/receipts/" + rid + "/returns", patrao.token(),
                Map.of("line_no", 1, "quantity", "4", "reason", "Mercadoria verde"), key());
        assertThat(ret.status()).as(ret.body()).isEqualTo(200);
        assertThat(ret.json().path("total_cost").asString()).isEqualTo("44.00"); // 4 × 11.00, não o médio
        UUID mov = UUID.fromString(ret.json().path("movement_id").asString());
        UUID origin = jdbc.sql("SELECT id FROM stock_movements WHERE source_type = 'inventory_receipt' "
                + "AND source_id = :r").param("r", UUID.fromString(rid)).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT reversal_of FROM stock_movements WHERE id = :m").param("m", mov)
                .query(UUID.class).single()).isEqualTo(origin);
        JsonNode b = balance(patrao, p);
        assertThat(b.path("physical").asString()).isEqualTo("16");
        assertThat(b.path("value").asString()).isEqualTo("266.00");
        assertThat(api.get("/api/v1/inventory/receipts/" + rid, patrao.token()).json().path("lines").get(0)
                .path("returned_base_quantity").asString()).isEqualTo("4");

        // Estorno de perda aprovada: volta com o mesmo custo, vinculado ao movimento da perda.
        Api.Res l = api.postWith("/api/v1/inventory/losses", patrao.token(), Map.of("product_id", p, "quantity", "2",
                "reason", "ERRO_CONTAGEM"), key());
        String lid = l.json().path("id").asString();
        Api.Res ap = api.postWith("/api/v1/inventory/losses/" + lid + "/approve", patrao.token(), null,
                key("aprovar-perda-dv01", 1));
        assertThat(ap.json().path("total_cost").asString()).isEqualTo("33.25"); // 2 × 266/16
        assertThat(api.postWith("/api/v1/inventory/losses/" + lid + "/reverse", patrao.token(), Map.of(),
                key("estornar-perda-01", 2)).status()).isEqualTo(422); // motivo obrigatório
        Api.Res rv = api.postWith("/api/v1/inventory/losses/" + lid + "/reverse", patrao.token(),
                Map.of("reason", "Contagem refeita"), key("estornar-perda-02", 2));
        assertThat(rv.status()).as(rv.body()).isEqualTo(200);
        assertThat(rv.json().path("status").asString()).isEqualTo("REVERSED");
        JsonNode after = balance(patrao, p);
        assertThat(after.path("physical").asString()).isEqualTo("16");
        assertThat(after.path("value").asString()).isEqualTo("266.00");
        assertThat(sumMovements(p, "LOSS").add(sumMovements(p, "LOSS_REVERSAL"))).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT reversal_of FROM stock_movements WHERE kind = 'LOSS_REVERSAL' AND source_id = :s")
                .param("s", UUID.fromString(lid)).query(UUID.class).single())
                .isEqualTo(UUID.fromString(ap.json().path("movement_id").asString()));
    }

    @Test
    void livroDeEstoqueImutavelESaldoNuncaNegativo() {
        String p = product("CANA-IM", "DUZIA", true, List.of());
        receiveConfirmed(p, "1", "1.00", null);
        assertThatThrownBy(() -> jdbc.sql("UPDATE stock_movements SET quantity = 999 WHERE product_id = :p")
                .param("p", UUID.fromString(p)).update()).hasMessageContaining("somente inclusão");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM stock_movements WHERE product_id = :p")
                .param("p", UUID.fromString(p)).update()).hasMessageContaining("somente inclusão");
        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory_balances SET reserved = 5 WHERE product_id = :p")
                .param("p", UUID.fromString(p)).update()).hasMessageContaining("check");
    }

    @Test
    void movimentosEPerdasRespeitamEscopoECampoDeCusto() {
        String p = product("CANA-MV", "DUZIA", true, List.of());
        receiveConfirmed(p, "3", "2.00", null);
        Api.Res mine = api.get("/api/v1/inventory/movements?product_id=" + p, balcao.token());
        assertThat(mine.status()).isEqualTo(200);
        JsonNode m = mine.json().path("items").get(0);
        assertThat(m.path("kind").asString()).isEqualTo("RECEIPT");
        assertThat(m.path("total_cost").isNull()).isTrue();
        assertThat(api.get("/api/v1/inventory/movements?product_id=" + p, patrao.token()).json().path("items").get(0)
                .path("total_cost").asString()).isEqualTo("6.00");
        // Unidade 2 não vê saldo/movimento da matriz.
        assertThat(api.get("/api/v1/inventory/movements?product_id=" + p, recebimentoUnidade2.token()).json()
                .path("items").size()).isZero();
        assertThat(balance(recebimentoUnidade2, p)).isNull();
        // Sem capacidade de perdas: 403.
        assertThat(api.get("/api/v1/inventory/losses", balcao.token()).status()).isEqualTo(403);
    }
}
