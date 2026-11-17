package br.com.engenheiro.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.AppClients;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * F13 (APP-C-01, APP-C-02): app Contábil com o client próprio. A guia enviada aparece para o dono e a decisão volta ao
 * contador com histórico; o contador não recebe valor global (dashboard, relatórios, contas, extrato, auditoria geral);
 * a folha devolvida pode ser cancelada e reimportada (antes ficava presa pela competência).
 */
@IntegrationTest
class ContabilAppIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    static final String MONTH = YearMonth.from(TODAY).toString();
    static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    Api api;
    Org org;
    Person patrao;
    Person contador;
    Person balcao;
    String bank;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Fernanda Contadora");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Ana Balcão");
        fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Carlos Lima");
        assertThat(contador.client()).isEqualTo(AppClients.CONTABIL);
        bank = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco Sigiloso Fictício", "kind", "BANK",
                "opening_balance", "98765.43"), key()).json().path("id").asString();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static String v(JsonNode n) {
        return "\"" + n.path("version").asLong() + "\"";
    }

    JsonNode submittedTax(String amount) {
        Map<String, Object> body = new HashMap<>(Map.of("kind", "DAS", "description", "DAS do mês", "competence", MONTH,
                "due_date", TODAY.plusDays(10).toString(), "amount", amount));
        Api.Res c = api.postWith("/api/v1/tax-obligations", contador.token(), body, key());
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        String id = c.json().path("id").asString();
        assertThat(api.upload("/api/v1/tax-obligations/" + id + "/attachments", contador.token(), "das.pdf", "application/pdf", PDF)
                .status()).isEqualTo(201);
        Api.Res s = api.post("/api/v1/tax-obligations/" + id + "/submit", contador.token(), null, v(c.json()));
        assertThat(s.status()).as(s.body()).isEqualTo(200);
        return s.json();
    }

    static List<String> actions(JsonNode history) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : history.path("items")) {
            out.add(i.path("action").asString());
        }
        return out;
    }

    // ---------- Critério: guia enviada aparece no dono e a aprovação retorna ao contador ----------
    @Test
    void guiaEnviadaApareceNoDonoEDecisaoVoltaAoContadorComHistorico() {
        JsonNode sub = submittedTax("850.00");
        String id = sub.path("id").asString();
        // O dono vê na central de aprovações, no setor da contabilidade.
        JsonNode center = api.get("/api/v1/approvals", patrao.token()).json();
        boolean found = false;
        for (JsonNode i : center.path("items")) {
            found |= i.path("id").asString().equals(id) && i.path("amount").asString().equals("850.00");
        }
        assertThat(found).as(center.toString()).isTrue();
        // E cada movimento do contador aparece no setor ACCOUNTING com o app de origem.
        JsonNode sector = api.get("/api/v1/audit-events?sector=ACCOUNTING&limit=50", patrao.token()).json();
        assertThat(sector.toString()).contains("taxes.submit").contains(AppClients.CONTABIL).contains("Fernanda Contadora");

        Api.Res ap = api.postWith("/api/v1/tax-obligations/" + id + "/approval", patrao.token(),
                Map.of("note", "Pode pagar no vencimento"), Map.of("If-Match", v(sub), "Idempotency-Key", "t-" + UUID.randomUUID()));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        // A decisão volta ao contador: estado, quem decidiu e a observação — aprovada continua NÃO paga.
        JsonNode seen = api.get("/api/v1/tax-obligations/" + id, contador.token()).json();
        assertThat(seen.path("state").asString()).isEqualTo("APPROVED");
        assertThat(seen.path("decided_by_name").asString()).isEqualTo("Patrão Fictício");
        assertThat(seen.path("decided_note").asString()).isEqualTo("Pode pagar no vencimento");
        assertThat(seen.path("payable_state").asString()).isEqualTo("OPEN");
        // Histórico da decisão (C04) para quem vê a guia.
        Api.Res h = api.get("/api/v1/tax-obligations/" + id + "/history", contador.token());
        assertThat(h.status()).as(h.body()).isEqualTo(200);
        assertThat(actions(h.json())).containsExactly("taxes.create", "taxes.attach", "taxes.submit", "taxes.approve");
        JsonNode last = h.json().path("items").get(3);
        assertThat(last.path("actor_name").asString()).isEqualTo("Patrão Fictício");
        assertThat(last.path("note").asString()).isEqualTo("Pode pagar no vencimento");
        assertThat(h.json().path("items").get(0).path("client_id").asString()).isEqualTo(AppClients.CONTABIL);

        // Pagamento pelo dono: o histórico mostra que foi paga, sem a conta bancária nem o valor dela.
        Api.Res pay = api.postWith("/api/v1/tax-obligations/" + id + "/payments", patrao.token(), Map.of("fin_account_id", bank), key());
        assertThat(pay.status()).as(pay.body()).isEqualTo(200);
        Api.Res h2 = api.get("/api/v1/tax-obligations/" + id + "/history", contador.token());
        assertThat(actions(h2.json())).endsWith("taxes.pay");
        assertThat(h2.body()).doesNotContain(bank).doesNotContain("Banco Sigiloso").doesNotContain("98765");

        // Sem permissão de guias: nem o histórico (403), e ID inexistente não vaza (403 para quem não pode, 404 para quem pode).
        assertThat(api.get("/api/v1/tax-obligations/" + id + "/history", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/tax-obligations/" + UUID.randomUUID() + "/history", balcao.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/tax-obligations/" + UUID.randomUUID() + "/history", contador.token()).status()).isEqualTo(404);
        // Outra organização não lê o histórico.
        Org other = fx.org();
        Person outro = fx.person(other, other.branch1(), RoleTemplate.CONTADOR, "Contador de Outra Empresa");
        assertThat(api.get("/api/v1/tax-obligations/" + id + "/history", outro.token()).status()).isEqualTo(404);
    }

    // ---------- Critério: nenhum valor global chega ao contador por API ----------
    @Test
    void contadorNaoRecebeValorGlobal() {
        for (String path : List.of("/api/v1/reports/overview", "/api/v1/reports/summary", "/api/v1/finance/accounts",
                "/api/v1/finance/accounts/" + bank + "/statement", "/api/v1/finance/reconciliation", "/api/v1/audit-events",
                "/api/v1/cash-sessions")) {
            Api.Res r = api.get(path, contador.token());
            assertThat(r.status()).as(path + " → " + r.body()).isIn(403, 404);
            assertThat(r.body()).doesNotContain("98765");
        }
        // A central de aprovações não traz pendências nem contagens de outros setores para o contador.
        JsonNode center = api.get("/api/v1/approvals", contador.token()).json();
        assertThat(center.path("items")).isEmpty();
        for (JsonNode sc : center.path("sectors")) {
            assertThat(sc.path("pending").asInt()).as(sc.toString()).isZero();
        }
        // A sessão do app mostra só as capacidades do contador (o app monta as abas a partir disso).
        String me = api.get("/api/v1/me", contador.token()).body();
        assertThat(me).doesNotContain("dashboard.view").doesNotContain("reports.view").doesNotContain("company_cash")
                .doesNotContain("taxes.approve").doesNotContain("payroll.approve");
        // O mesmo contador, usando o client de outro app, é recusado.
        assertThat(api.get("/api/v1/tax-obligations", contador.tokenFor(AppClients.RETIRADA)).status()).isEqualTo(403);
    }

    // ---------- Folha devolvida: cancelar e reimportar; concorrência no cancelamento ----------
    @Test
    void folhaDevolvidaPodeSerCanceladaEReimportadaSemDuplicar() throws Exception {
        String csv = "funcionario;rubrica;tipo;valor\nCarlos Lima;Salário base;SALARIO;3.000,00\nCarlos Lima;FGTS;ENCARGO;240,00\n";
        Map<String, String> head = Map.of("competence", MONTH, "description", "Folha mensal", "pay_date", TODAY.plusDays(5).toString());
        JsonNode run = api.uploadWith("/api/v1/payroll-runs/imports", contador.token(), head, "folha.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)).json();
        String rid = run.path("id").asString();
        JsonNode sub = api.post("/api/v1/payroll-runs/" + rid + "/submit", contador.token(), null, v(run)).json();
        // Enviada não se cancela.
        assertThat(api.post("/api/v1/payroll-runs/" + rid + "/cancel", contador.token(), Map.of("note", "refazer"), v(sub))
                .status()).isEqualTo(409);
        JsonNode ret = api.post("/api/v1/payroll-runs/" + rid + "/return", patrao.token(), Map.of("note", "Faltou o vale"), v(sub)).json();
        assertThat(ret.path("state").asString()).isEqualTo("RETURNED");
        // A versão corrigida não entra enquanto a devolvida ocupa a competência.
        String fixed = csv + "Carlos Lima;Vale-transporte;BENEFICIO;200,00\n";
        assertThat(api.uploadWith("/api/v1/payroll-runs/imports", contador.token(), head, "folha2.csv", "text/csv",
                fixed.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(409);
        // Sem motivo, sem permissão ou com versão velha: recusado.
        assertThat(api.post("/api/v1/payroll-runs/" + rid + "/cancel", contador.token(), Map.of("note", " "), v(ret)).status())
                .isEqualTo(422);
        assertThat(api.post("/api/v1/payroll-runs/" + rid + "/cancel", balcao.token(), Map.of("note", "x"), v(ret)).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/payroll-runs/" + rid + "/cancel", contador.token(), Map.of("note", "x"), v(run)).status())
                .isEqualTo(412);
        // Dois cancelamentos simultâneos com a mesma versão: só um passa.
        var pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(() -> api.post("/api/v1/payroll-runs/" + rid + "/cancel", contador.token(),
                    Map.of("note", "Refazer com o vale"), v(ret)).status());
        }
        List<Integer> st = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(calls)) {
            st.add(f.get());
        }
        pool.shutdown();
        // O perdedor espera a trava e é recusado: versão velha (412) ou já cancelada (409). Nunca dois 200.
        assertThat(st).containsOnlyOnce(200);
        assertThat(st.stream().filter(x -> x != 200).toList()).allMatch(x -> x == 409 || x == 412);
        JsonNode cancelled = api.get("/api/v1/payroll-runs/" + rid, contador.token()).json();
        assertThat(cancelled.path("state").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("net_payable_id").isNull()).isTrue(); // nada foi lançado
        // A decisão do patrão continua sendo dele; o motivo do contador não é atribuído ao patrão.
        assertThat(cancelled.path("decided_by_name").asString()).isEqualTo("Patrão Fictício");
        assertThat(cancelled.path("decided_note").asString()).isEqualTo("Faltou o vale");
        // Agora a corrigida entra.
        Api.Res again = api.uploadWith("/api/v1/payroll-runs/imports", contador.token(), head, "folha2.csv", "text/csv",
                fixed.getBytes(StandardCharsets.UTF_8));
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        assertThat(again.json().path("totals").path("benefits").asString()).isEqualTo("200.00");
        // Histórico da folha: preparou, enviou, patrão devolveu com motivo, contador cancelou.
        JsonNode h = api.get("/api/v1/payroll-runs/" + rid + "/history", contador.token()).json();
        assertThat(actions(h)).containsExactly("payroll.create", "payroll.submit", "payroll.return", "payroll.cancel");
        assertThat(h.path("items").get(2).path("note").asString()).isEqualTo("Faltou o vale");
        assertThat(h.toString()).doesNotContain("3000").doesNotContain("Carlos Lima"); // sem valor individual
        assertThat(api.get("/api/v1/payroll-runs/" + rid + "/history", balcao.token()).status()).isEqualTo(403);
    }
}
