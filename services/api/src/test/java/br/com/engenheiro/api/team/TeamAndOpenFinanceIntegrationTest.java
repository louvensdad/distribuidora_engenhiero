package br.com.engenheiro.api.team;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.finance.openfinance.OpenFinanceProvider.Tx;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import br.com.engenheiro.api.support.TestOpenFinanceProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/** Pedido do responsável (ADR-021): folgas/férias com calendário por setor, desempenho por função e Open Finance. */
@IntegrationTest
class TeamAndOpenFinanceIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    static final String CNPJ = "11222333000181"; // CNPJ fictício de exemplo, dígitos válidos
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    Api api;
    Org org;
    Person patrao;
    Person gerente;
    Person aprovador;
    Person balcao;
    Person balcao2;
    Person motorista;

    @BeforeEach
    void setUp() {
        TestOpenFinanceProvider.reset();
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        gerente = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Gerente");
        aprovador = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Aprovador");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Ana Balcão");
        balcao2 = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Bruno Balcão");
        motorista = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Carlos Motorista");
        fx.grant(gerente, "users", "view", "ORGANIZATION", null);
        fx.grant(gerente, "users", "edit_draft", "ORGANIZATION", null);
        for (String a : List.of("view", "approve", "confirm", "reverse")) {
            fx.grant(aprovador, "expenses", a, "ORGANIZATION", null);
        }
        fx.grant(aprovador, "company_cash", "view", "ORGANIZATION", null);
    }

    @AfterEach
    void tearDown() {
        TestOpenFinanceProvider.reset();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static Map<String, String> key(long v) {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID(), "If-Match", "\"" + v + "\"");
    }

    JsonNode absence(Person who, Person employee, String kind, LocalDate start, LocalDate end) {
        Api.Res r = api.post("/api/v1/team/absences", who.token(), Map.of("membership_id", employee.membershipId().toString(),
                "kind", kind, "start_date", start.toString(), "end_date", end.toString(), "notes", "teste"), (String) null);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    Api.Res approveAbsence(Person who, JsonNode a) {
        return api.post("/api/v1/team/absences/" + a.path("id").asString() + "/approve", who.token(), Map.of(),
                "\"" + a.path("version").asLong() + "\"");
    }

    // ---------- Folgas e férias: calendário por setor, sem sobreposição, aviso de setor descoberto ----------
    @Test
    void feriasEFolgasComCalendarioPorSetorESemSobreposicao() {
        LocalDate s = TODAY.plusDays(10);
        JsonNode ferias = absence(gerente, balcao, "VACATION", s, s.plusDays(9));
        assertThat(ferias.path("state").asString()).isEqualTo("REQUESTED");
        assertThat(ferias.path("days").asLong()).isEqualTo(10);
        // Quem não tem a capacidade não registra; a própria ausência não é aprovada pela pessoa.
        assertThat(api.post("/api/v1/team/absences", balcao.token(), Map.of("membership_id", balcao.membershipId().toString(),
                "kind", "DAY_OFF", "start_date", s.toString(), "end_date", s.toString()), (String) null).status()).isEqualTo(403);
        JsonNode propria = absence(gerente, gerente, "DAY_OFF", s, s);
        assertThat(approveAbsence(gerente, propria).status()).isEqualTo(403);
        Api.Res ok = approveAbsence(gerente, ferias);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().path("state").asString()).isEqualTo("APPROVED");
        // Mesma pessoa, período sobreposto: recusado.
        JsonNode sobre = absence(gerente, balcao, "DAY_OFF", s.plusDays(3), s.plusDays(3));
        Api.Res overlap = approveAbsence(gerente, sobre);
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.body()).contains("já tem ausência aprovada");
        // Setor de entregas com um só motorista: aprovar a folga avisa que o setor fica sem ninguém (não bloqueia).
        JsonNode folga = absence(gerente, motorista, "DAY_OFF", s, s);
        Api.Res aviso = approveAbsence(gerente, folga);
        assertThat(aviso.status()).isEqualTo(200);
        assertThat(aviso.json().path("warnings").toString()).contains("setor ENTREGAS sem ninguém disponível");
        // Calendário: no dia s, Ana fora (férias), entregas descoberto; retirada ainda com gente.
        JsonNode cal = api.get("/api/v1/team/calendar?from=" + s + "&to=" + s.plusDays(1), gerente.token()).json();
        JsonNode day = cal.path("days").get(0);
        Map<String, JsonNode> bySector = new HashMap<>();
        day.path("sectors").forEach(x -> bySector.put(x.path("sector").asString(), x));
        assertThat(bySector.get("RETIRADA").path("absent").toString()).contains("Ana Balcão (férias)");
        assertThat(bySector.get("RETIRADA").path("uncovered").asBoolean()).isFalse();
        assertThat(bySector.get("ENTREGAS").path("uncovered").asBoolean()).isTrue();
        assertThat(api.get("/api/v1/team/calendar", balcao.token()).status()).isEqualTo(403);
        // Férias já gozadas nos últimos 12 meses.
        JsonNode passada = absence(gerente, balcao2, "VACATION", TODAY.minusDays(20), TODAY.minusDays(11));
        approveAbsence(gerente, passada);
        JsonNode cal2 = api.get("/api/v1/team/calendar", gerente.token()).json();
        for (JsonNode m : cal2.path("members")) {
            if (m.path("person").asString().equals("Bruno Balcão")) {
                assertThat(m.path("vacation_days_last_12m").asLong()).isEqualTo(10);
            }
        }
    }

    // ---------- Desempenho por função: medidas separadas, fórmula pública, sem nota única ----------
    @Test
    void desempenhoPorFuncaoComMedidasSeparadasEFormula() {
        fx.grant(balcao, "own_cash", "create", "OWN", null);
        fx.grant(balcao, "own_cash", "view", "OWN", null);
        fx.grant(balcao, "own_cash", "close", "OWN", null);
        JsonNode s = api.postWith("/api/v1/cash-sessions", balcao.token(), Map.of("opening_float", "50.00"), key()).json();
        assertThat(api.postWith("/api/v1/cash-sessions/" + s.path("id").asString() + "/close", balcao.token(),
                Map.of("counted", "48.00", "justification", "troco errado"), key(s.path("version").asLong())).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/team/performance", balcao.token()).status()).isEqualTo(403);
        JsonNode r = api.get("/api/v1/team/performance", patrao.token()).json();
        assertThat(r.path("rule").asString()).contains("Sem nota única");
        JsonNode cash = null;
        for (JsonNode m : r.path("measures")) {
            assertThat(m.path("formula").asString()).isNotBlank();
            if (m.path("code").asString().equals("CASH_DIFFERENCE")) {
                cash = m;
            }
        }
        assertThat(cash.path("higher_is_better").asBoolean()).isFalse();
        assertThat(cash.path("values").get(0).path("person").asString()).isEqualTo("Ana Balcão");
        assertThat(cash.path("values").get(0).path("value").asString()).isEqualTo("2.00");
        assertThat(cash.path("leader").isNull()).isTrue(); // só uma pessoa: sem comparação
        assertThat(cash.path("note").asString()).contains("sem comparação");
        assertThat(r.toString()).doesNotContain("score").doesNotContain("nota_final");
    }

    // ---------- Open Finance sem agregador: NÃO CONFIGURADO, nada simulado ----------
    @Test
    void openFinanceSemAgregadorFicaNaoConfigurado() {
        JsonNode st = api.get("/api/v1/finance/open-finance", patrao.token()).json();
        assertThat(st.path("state").asString()).isEqualTo("NAO_CONFIGURADO");
        assertThat(st.path("message").asString()).contains("NÃO CONFIGURADO");
        String bank = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco Fictício", "kind", "BANK"), key())
                .json().path("id").asString();
        Api.Res c = api.post("/api/v1/finance/open-finance/connections", patrao.token(), Map.of("fin_account_id", bank,
                "institution", "Banco Fictício", "company_document", CNPJ), (String) null);
        assertThat(c.status()).isEqualTo(503);
        assertThat(c.body()).contains("NÃO CONFIGURADO");
        assertThat(api.get("/api/v1/finance/open-finance/transactions", patrao.token()).json().path("items")).isEmpty();
    }

    // ---------- Open Finance com agregador: concilia o que existe, sugere o novo, nunca duplica ----------
    @Test
    void extratoImportadoConciliaSugereDespesaENaoDuplica() {
        TestOpenFinanceProvider.enabled = true;
        String bank = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco Fictício", "kind", "BANK",
                "opening_balance", "2000.00"), key()).json().path("id").asString();
        String safe = null;
        for (JsonNode a : api.get("/api/v1/finance/accounts", patrao.token()).json().path("items")) {
            if (a.path("code").asString().equals("COFRE_EMPRESA")) {
                safe = a.path("id").asString();
            }
        }
        // Já no sistema: conta de luz de R$ 300 paga por este banco e transferência de R$ 100 para o cofre.
        JsonNode e = api.postWith("/api/v1/expenses", patrao.token(), Map.of("category", "ESTRUTURA", "subcategory", "Energia",
                "description", "Conta de luz", "amount", "300.00"), key()).json();
        JsonNode ap = api.postWith("/api/v1/expenses/" + e.path("id").asString() + "/approve", patrao.token(), Map.of(),
                key(e.path("version").asLong())).json();
        assertThat(api.postWith("/api/v1/payables/" + ap.path("payable_id").asString() + "/payments", patrao.token(),
                Map.of("amount", "300.00", "fin_account_id", bank), key()).status()).isEqualTo(200);
        assertThat(api.postWith("/api/v1/finance/transfers", patrao.token(), Map.of("from_account_id", bank, "to_account_id", safe,
                "amount", "100.00"), key()).status()).isEqualTo(201);

        assertThat(api.post("/api/v1/finance/open-finance/connections", patrao.token(), Map.of("fin_account_id", bank,
                "institution", "Banco Fictício", "company_document", "11222333000100"), (String) null).status()).isEqualTo(422);
        assertThat(api.post("/api/v1/finance/open-finance/connections", aprovador.token(), Map.of("fin_account_id", bank,
                "institution", "Banco Fictício", "company_document", CNPJ), (String) null).status()).isEqualTo(403);
        Api.Res con = api.post("/api/v1/finance/open-finance/connections", patrao.token(), Map.of("fin_account_id", bank,
                "institution", "Banco Fictício", "company_document", "11.222.333/0001-81"), (String) null);
        assertThat(con.status()).as(con.body()).isEqualTo(201);
        assertThat(con.json().path("state").asString()).isEqualTo("PENDING_CONSENT");
        assertThat(con.json().path("consent_url").asString()).startsWith("https://agregador.teste.invalid/");
        String conn = con.json().path("id").asString();

        TestOpenFinanceProvider.balance = new BigDecimal("1025.00"); // 2000 − 300 − 100 − 450 − 200 + 75
        TestOpenFinanceProvider.TRANSACTIONS.addAll(List.of(
                new Tx("t1", TODAY, new BigDecimal("300.00"), "OUT", "PAGTO CONTA ENERGIA", "Distribuidora de Energia"),
                new Tx("t2", TODAY, new BigDecimal("100.00"), "OUT", "SAQUE", null),
                new Tx("t3", TODAY, new BigDecimal("450.00"), "OUT", "PAGTO DAS SIMPLES NACIONAL", "Receita Federal"),
                new Tx("t4", TODAY, new BigDecimal("200.00"), "OUT", "TED FULANO DE TAL", "Fulano"),
                new Tx("t5", TODAY, new BigDecimal("75.00"), "IN", "PIX RECEBIDO", "Cliente X")));
        JsonNode sync = api.post("/api/v1/finance/open-finance/connections/" + conn + "/sync", patrao.token(), null, (String) null).json();
        assertThat(sync.path("imported").asInt()).isEqualTo(5);
        assertThat(sync.path("matched").asInt()).isEqualTo(2);   // luz já paga e transferência
        assertThat(sync.path("suggested").asInt()).isEqualTo(2); // DAS e TED viram despesas sugeridas
        assertThat(sync.path("unmatched").asInt()).isEqualTo(1); // entrada nunca vira receita automática
        assertThat(sync.path("connection").path("state").asString()).isEqualTo("ACTIVE");
        // Sincronizar de novo não duplica nada.
        assertThat(api.post("/api/v1/finance/open-finance/connections/" + conn + "/sync", patrao.token(), null, (String) null)
                .json().path("imported").asInt()).isZero();
        // Despesa já paga continua uma só (300), sem sugestão duplicada.
        JsonNode sug = api.get("/api/v1/finance/open-finance/transactions?state=SUGGESTED", aprovador.token()).json().path("items");
        Map<String, JsonNode> byDesc = new HashMap<>();
        sug.forEach(x -> byDesc.put(x.path("description").asString(), x));
        assertThat(byDesc).containsOnlyKeys("PAGTO DAS SIMPLES NACIONAL", "TED FULANO DE TAL");
        assertThat(byDesc.get("PAGTO DAS SIMPLES NACIONAL").path("suggested_category").asString()).isEqualTo("IMPOSTOS_TAXAS");

        // Aprovar a sugestão do DAS: despesa uma vez + pagamento pelo banco na mesma hora, transação conciliada.
        String das = byDesc.get("PAGTO DAS SIMPLES NACIONAL").path("suggested_expense_id").asString();
        long v = api.get("/api/v1/expenses/" + das, aprovador.token()).json().path("version").asLong();
        Api.Res apDas = api.postWith("/api/v1/expenses/" + das + "/approve", aprovador.token(), Map.of(), key(v));
        assertThat(apDas.status()).as(apDas.body()).isEqualTo(200);
        assertThat(apDas.json().path("payable_state").asString()).isEqualTo("PAID");
        // Recusar a TED: volta para conferência e pode ser ignorada com motivo.
        String ted = byDesc.get("TED FULANO DE TAL").path("suggested_expense_id").asString();
        long vt = api.get("/api/v1/expenses/" + ted, aprovador.token()).json().path("version").asLong();
        assertThat(api.post("/api/v1/expenses/" + ted + "/reject", aprovador.token(), Map.of("note", "é retirada do sócio"),
                "\"" + vt + "\"").status()).isEqualTo(200);
        JsonNode unmatched = api.get("/api/v1/finance/open-finance/transactions?state=UNMATCHED", aprovador.token()).json().path("items");
        assertThat(unmatched).hasSize(2);
        String tedTx = null;
        for (JsonNode x : unmatched) {
            if (x.path("description").asString().startsWith("TED")) {
                tedTx = x.path("id").asString();
            }
        }
        assertThat(api.post("/api/v1/finance/open-finance/transactions/" + tedTx + "/ignore", aprovador.token(),
                Map.of("reason", "retirada do sócio — lançar como retirada"), (String) null).json().path("state").asString())
                .isEqualTo("IGNORED");

        // Resultado: impostos 450 uma vez; estrutura 300 uma vez.
        JsonNode s = api.get("/api/v1/reports/summary", patrao.token()).json();
        Map<String, String> exp = new HashMap<>();
        s.path("expenses").forEach(x -> exp.put(x.path("key").asString(), x.path("amount").asString()));
        assertThat(exp).containsEntry("IMPOSTOS_TAXAS", "450.00").containsEntry("ESTRUTURA", "300.00");
        // Conciliação do banco: livro 2000 − 300 − 100 − 450 = 1.150; banco diz 1.025 (TED de 200 e entrada de 75 ainda
        // não lançadas) → divergência explicada pelas transações pendentes.
        JsonNode rec = api.get("/api/v1/finance/reconciliation", patrao.token()).json();
        JsonNode bankCheck = null;
        for (JsonNode c : rec.path("checks")) {
            if (c.path("code").asString().startsWith("BANK_")) {
                bankCheck = c;
            }
        }
        assertThat(bankCheck.path("expected").asString()).isEqualTo("1025.00");
        assertThat(bankCheck.path("actual").asString()).isEqualTo("1150.00");
        assertThat(bankCheck.path("status").asString()).isEqualTo("DIVERGENT");
        assertThat(bankCheck.path("explanation").asString()).contains("aguardando conferência");
        // Revogar a conexão.
        assertThat(api.post("/api/v1/finance/open-finance/connections/" + conn + "/revoke", patrao.token(),
                Map.of("reason", "troca de agregador"), (String) null).json().path("state").asString()).isEqualTo("REVOKED");
    }
}
