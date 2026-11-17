package br.com.engenheiro.api.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.shared.ids.Ids;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** F07: guia em etapas, folha por rubricas com dado individual protegido, central de documentos e supervisão do patrão. */
@IntegrationTest
class AccountingIntegrationTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    static final String MONTH = YearMonth.from(TODAY).toString();
    static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 'I', 'E', 'N', 'D',
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;
    @Autowired
    JdbcClient jdbc;
    Api api;
    Org org;
    Person patrao;
    Person contador;
    Person ana;
    Person carlos;
    Person balcao;
    String bank;

    @BeforeEach
    void setUp() {
        api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        org = fx.org();
        patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão");
        contador = fx.person(org, org.branch1(), RoleTemplate.CONTADOR, "Contador");
        ana = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Ana Souza");
        carlos = fx.person(org, org.branch1(), RoleTemplate.MOTORISTA, "Carlos Lima");
        balcao = fx.person(org, org.branch1(), RoleTemplate.BALCAO, "Balcão");
        bank = api.postWith("/api/v1/finance/accounts", patrao.token(), Map.of("name", "Banco Fictício", "kind", "BANK",
                "opening_balance", "20000.00"), key()).json().path("id").asString();
    }

    static Map<String, String> key() {
        return Map.of("Idempotency-Key", "teste-" + UUID.randomUUID());
    }

    static String v(JsonNode n) {
        return "\"" + n.path("version").asLong() + "\"";
    }

    JsonNode guia(String kind, String amount, UUID payrollRun) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("description", "Guia " + kind + " de teste");
        body.put("competence", MONTH);
        body.put("due_date", TODAY.plusDays(15).toString());
        body.put("amount", amount);
        body.put("payroll_run_id", payrollRun == null ? null : payrollRun.toString());
        Api.Res r = api.postWith("/api/v1/tax-obligations", contador.token(), body, key());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.json();
    }

    String expenseOf(String category) {
        for (JsonNode e : api.get("/api/v1/reports/summary", patrao.token()).json().path("expenses")) {
            if (e.path("key").asString().equals(category)) {
                return e.path("amount").asString();
            }
        }
        throw new AssertionError(category);
    }

    // ---------- Critérios: upload real ≠ nome; guia aprovada continua não paga até a confirmação ----------
    @Test
    void guiaEmEtapasComUploadRealEAprovacaoQueNaoPaga() {
        JsonNode g = guia("DAS", "850.00", null);
        String id = g.path("id").asString();
        assertThat(g.path("state").asString()).isEqualTo("DRAFT");
        // Enviar sem arquivo: recusado. Só o nome (sem bytes) ou bytes que não são PDF: recusados.
        assertThat(api.post("/api/v1/tax-obligations/" + id + "/submit", contador.token(), null, v(g)).status()).isEqualTo(422);
        assertThat(api.post("/api/v1/tax-obligations/" + id + "/attachments", contador.token(), Map.of("file_name", "guia.pdf"),
                (String) null).status()).isIn(400, 415, 422);
        Api.Res falso = api.upload("/api/v1/tax-obligations/" + id + "/attachments", contador.token(), "guia.pdf",
                "application/pdf", "isto não é um pdf".getBytes(StandardCharsets.UTF_8));
        assertThat(falso.json().path("state").asString()).isEqualTo("REJECTED");
        assertThat(api.post("/api/v1/tax-obligations/" + id + "/submit", contador.token(), null, v(g)).status()).isEqualTo(422);
        Api.Res real = api.upload("/api/v1/tax-obligations/" + id + "/attachments", contador.token(), "guia-das.pdf",
                "application/pdf", PDF);
        assertThat(real.status()).as(real.body()).isEqualTo(201);
        assertThat(real.json().path("state").asString()).isEqualTo("AVAILABLE");
        assertThat(real.json().path("size_bytes").asLong()).isEqualTo(PDF.length);
        // Os bytes guardados são os enviados (download autorizado devolve o mesmo conteúdo).
        Api.Res dl = api.getBytes("/api/v1/documents/" + real.json().path("id").asString() + "/download", patrao.token());
        assertThat(dl.status()).isEqualTo(200);
        assertThat(dl.body().getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(PDF);
        assertThat(api.getBytes("/api/v1/documents/" + real.json().path("id").asString() + "/download", balcao.token())
                .status()).isEqualTo(403);

        JsonNode sub = api.post("/api/v1/tax-obligations/" + id + "/submit", contador.token(), null, v(g)).json();
        assertThat(sub.path("state").asString()).isEqualTo("SUBMITTED");
        // Contador não aprova nem paga.
        assertThat(api.postWith("/api/v1/tax-obligations/" + id + "/approval", contador.token(), Map.of(),
                Map.of("If-Match", v(sub))).status()).isEqualTo(403);
        Api.Res ap = api.postWith("/api/v1/tax-obligations/" + id + "/approval", patrao.token(), Map.of("note", "ok"),
                Map.of("If-Match", v(sub), "Idempotency-Key", "t-" + UUID.randomUUID()));
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(ap.json().path("state").asString()).isEqualTo("APPROVED");
        assertThat(ap.json().path("payable_state").asString()).isEqualTo("OPEN"); // aprovada continua não paga
        assertThat(expenseOf("IMPOSTOS_TAXAS")).isEqualTo("850.00");
        JsonNode stmt0 = api.get("/api/v1/finance/accounts/" + bank + "/statement", patrao.token()).json();
        assertThat(stmt0.path("closing_balance").asString()).isEqualTo("20000.00");
        assertThat(api.postWith("/api/v1/tax-obligations/" + id + "/payments", contador.token(), Map.of("fin_account_id", bank),
                key()).status()).isEqualTo(403);
        // Pagamento com comprovante anexado pelo patrão.
        Api.Res comp = api.upload("/api/v1/tax-obligations/" + id + "/attachments", patrao.token(), "comprovante.png", "image/png", PNG);
        assertThat(comp.status()).isEqualTo(201);
        Api.Res pay = api.postWith("/api/v1/tax-obligations/" + id + "/payments", patrao.token(), Map.of("fin_account_id", bank,
                "proof_document_id", comp.json().path("id").asString()), key());
        assertThat(pay.status()).as(pay.body()).isEqualTo(200);
        assertThat(pay.json().path("state").asString()).isEqualTo("PAID");
        assertThat(expenseOf("IMPOSTOS_TAXAS")).isEqualTo("850.00"); // sem segunda despesa
        assertThat(api.get("/api/v1/finance/accounts/" + bank + "/statement", patrao.token()).json().path("closing_balance")
                .asString()).isEqualTo("19150.00");

        // Devolução: volta para o contador, que corrige e reenvia.
        JsonNode g2 = guia("ICMS", "300.00", null);
        String id2 = g2.path("id").asString();
        api.upload("/api/v1/tax-obligations/" + id2 + "/attachments", contador.token(), "icms.pdf", "application/pdf", PDF);
        JsonNode s2 = api.post("/api/v1/tax-obligations/" + id2 + "/submit", contador.token(), null, v(g2)).json();
        JsonNode ret = api.post("/api/v1/tax-obligations/" + id2 + "/return", patrao.token(), Map.of("note", "valor errado"), v(s2)).json();
        assertThat(ret.path("state").asString()).isEqualTo("RETURNED");
        assertThat(ret.path("decided_note").asString()).isEqualTo("valor errado");
        Map<String, Object> fix = new HashMap<>(Map.of("kind", "ICMS", "description", "Guia ICMS corrigida", "competence", MONTH,
                "due_date", TODAY.plusDays(15).toString(), "amount", "320.00"));
        JsonNode upd = api.post("/api/v1/tax-obligations/" + id2 + "/draft", contador.token(), fix, v(ret)).json();
        assertThat(upd.path("amount").asString()).isEqualTo("320.00");
        assertThat(api.post("/api/v1/tax-obligations/" + id2 + "/submit", contador.token(), null, v(upd)).json().path("state")
                .asString()).isEqualTo("SUBMITTED");

        // CONT-05: o patrão vê cada movimento do contador, com o app de origem.
        List<String> acts = new ArrayList<>();
        for (JsonNode e : api.get("/api/v1/audit-events?client_id=engenheiro-contabil&limit=50", patrao.token()).json().path("items")) {
            acts.add(e.path("action").asString());
        }
        assertThat(acts).contains("taxes.create", "taxes.attach", "taxes.submit", "taxes.update");
    }

    // ---------- Critério: contador sem permissão individual não recebe salários pessoais (JSON nem export) ----------
    @Test
    void folhaPorRubricasComDadoIndividualProtegido() {
        String csv = "funcionario;rubrica;tipo;valor\n"
                + "Ana Souza;Salário base;SALARIO;3.000,00\n"
                + "Ana Souza;Vale-transporte;BENEFICIO;200,00\n"
                + "Ana Souza;INSS empregado;DESCONTO;270,00\n"
                + "Ana Souza;Adiantamento quinzenal;ADIANTAMENTO;1.000,00\n"
                + "Ana Souza;FGTS;ENCARGO;240,00\n"
                + "Carlos Lima;Salário base;SALARIO;3.000,00\n"
                + "Carlos Lima;Vale-refeição;BENEFICIO;600,00\n"
                + "Carlos Lima;INSS empregado;DESCONTO;270,00\n"
                + "Carlos Lima;FGTS;ENCARGO;960,00\n";
        Map<String, String> head = Map.of("competence", MONTH, "description", "Folha mensal", "pay_date", TODAY.plusDays(5).toString());
        // Arquivo com funcionário inexistente: recusado inteiro, com a linha.
        Api.Res bad = api.uploadWith("/api/v1/payroll-runs/imports", contador.token(), head, "folha.csv", "text/csv",
                (csv + "Fulano Inexistente;Salário;SALARIO;1,00\n").getBytes(StandardCharsets.UTF_8));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body()).contains("linha 11").contains("não cadastrado");
        int runs = jdbc.sql("SELECT count(*) FROM payroll_runs WHERE organization_id = :o").param("o", org.id()).query(Integer.class).single();
        assertThat(runs).isZero();

        Api.Res imp = api.uploadWith("/api/v1/payroll-runs/imports", contador.token(), head, "folha.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        assertThat(imp.status()).as(imp.body()).isEqualTo(201);
        JsonNode run = imp.json();
        String rid = run.path("id").asString();
        // Contador sem `employee_salary`: totais e rubricas, nunca a pessoa.
        assertThat(run.path("people").isNull()).isTrue();
        assertThat(run.path("masked_fields").toString()).contains("people");
        assertThat(run.path("totals").path("earnings").asString()).isEqualTo("6000.00");
        assertThat(run.path("totals").path("benefits").asString()).isEqualTo("800.00");
        assertThat(run.path("totals").path("employer_charges").asString()).isEqualTo("1200.00");
        assertThat(run.path("totals").path("company_cost").asString()).isEqualTo("8000.00"); // W10: total R$ 8.000
        assertThat(run.path("totals").path("net_pay").asString()).isEqualTo("4460.00");
        String body = imp.body();
        assertThat(body).doesNotContain("Ana Souza").doesNotContain("Carlos Lima");
        assertThat(api.getBytes("/api/v1/payroll-runs/" + rid + "/export", contador.token()).status()).isEqualTo(403);
        fx.grant(contador, "payroll", "export", "ORGANIZATION", null);
        Api.Res exp = api.getBytes("/api/v1/payroll-runs/" + rid + "/export", contador.token());
        assertThat(exp.status()).isEqualTo(200);
        String text = new String(exp.body().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        assertThat(text).startsWith("rubrica;tipo;valor;pessoas").doesNotContain("Ana Souza").doesNotContain("Carlos Lima");
        // Auditoria sem valor individual.
        String audit = jdbc.sql("SELECT string_agg(changes::text, ' ') FROM audit_events WHERE entity_id = :id")
                .param("id", rid).query(String.class).single();
        assertThat(audit).doesNotContain("3000").doesNotContain("Ana");
        // Com a permissão individual concedida pelo dono, a pessoa aparece.
        jdbc.sql("INSERT INTO field_policies (id, membership_id, field_class, effect) VALUES (:id, :m, 'employee_salary', 'ALLOW')")
                .param("id", Ids.newId()).param("m", contador.membershipId()).update();
        JsonNode withPeople = api.get("/api/v1/payroll-runs/" + rid, contador.token()).json();
        assertThat(withPeople.path("people")).hasSize(2);
        jdbc.sql("UPDATE field_policies SET revoked_at = now() WHERE membership_id = :m").param("m", contador.membershipId()).update();

        // Etapas: enviar → aprovar (outra pessoa) → pagar. Despesa uma vez; adiantamento compensado.
        JsonNode sub = api.post("/api/v1/payroll-runs/" + rid + "/submit", contador.token(), null, v(run)).json();
        assertThat(api.post("/api/v1/payroll-runs/" + rid + "/approval", contador.token(), Map.of(), v(sub)).status()).isEqualTo(403);
        JsonNode ap = api.post("/api/v1/payroll-runs/" + rid + "/approval", patrao.token(), Map.of(), v(sub)).json();
        assertThat(ap.path("state").asString()).isEqualTo("APPROVED");
        assertThat(ap.path("people")).hasSize(2); // o patrão (administrador) vê o individual
        assertThat(expenseOf("FUNCIONARIOS")).isEqualTo("8000.00");
        JsonNode pay = api.post("/api/v1/payroll-runs/" + rid + "/payments", patrao.token(), Map.of("fin_account_id", bank), (String) null).json();
        assertThat(pay.path("state").asString()).isEqualTo("PAID");
        assertThat(expenseOf("FUNCIONARIOS")).isEqualTo("8000.00");
        assertThat(api.get("/api/v1/finance/accounts/" + bank + "/statement", patrao.token()).json().path("closing_balance")
                .asString()).isEqualTo("15540.00"); // 20.000 − 4.460 líquidos

        // Guia de FGTS ligada à folha: paga o encargo já reconhecido, sem nova despesa.
        JsonNode fgts = guia("FGTS", "1200.00", UUID.fromString(rid));
        api.upload("/api/v1/tax-obligations/" + fgts.path("id").asString() + "/attachments", contador.token(), "fgts.pdf", "application/pdf", PDF);
        JsonNode fs = api.post("/api/v1/tax-obligations/" + fgts.path("id").asString() + "/submit", contador.token(), null, v(fgts)).json();
        JsonNode fa = api.postWith("/api/v1/tax-obligations/" + fgts.path("id").asString() + "/approval", patrao.token(), Map.of(),
                Map.of("If-Match", v(fs), "Idempotency-Key", "t-" + UUID.randomUUID())).json();
        assertThat(fa.path("state").asString()).isEqualTo("APPROVED");
        assertThat(api.postWith("/api/v1/tax-obligations/" + fgts.path("id").asString() + "/payments", patrao.token(),
                Map.of("fin_account_id", bank), key()).json().path("state").asString()).isEqualTo("PAID");
        assertThat(expenseOf("FUNCIONARIOS")).isEqualTo("8000.00");
        assertThat(expenseOf("IMPOSTOS_TAXAS")).isEqualTo("0.00");
        assertThat(api.get("/api/v1/finance/reconciliation", patrao.token()).json().path("status").asString()).isEqualTo("OK");
    }

    // ---------- Central de documentos: versões, solicitação, download autorizado ----------
    @Test
    void centralDeDocumentosComVersoesESolicitacoes() {
        // "Só o nome" não cria documento.
        Api.Res semArquivo = api.uploadWith("/api/v1/documents/uploads", contador.token(), Map.of("kind", "CONTRATO",
                "title", "Contrato social"), null, null, null);
        assertThat(semArquivo.status()).isIn(400, 422);
        Api.Res falso = api.uploadWith("/api/v1/documents/uploads", contador.token(), Map.of("kind", "CONTRATO",
                "title", "Contrato social"), "contrato.pdf", "application/pdf", "%PDF-1.4 /JavaScript (app.alert(1))".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(falso.status()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM accounting_documents WHERE organization_id = :o").param("o", org.id())
                .query(Integer.class).single()).isZero();

        // Solicitação do contador ao patrão, atendida com o envio.
        Api.Res req = api.post("/api/v1/document-requests", contador.token(), Map.of("title", "Extrato bancário de " + MONTH,
                "kind", "EXTRATO", "competence", MONTH, "requested_from", patrao.userId().toString()), (String) null);
        assertThat(req.status()).as(req.body()).isEqualTo(201);
        Api.Res up = api.uploadWith("/api/v1/documents/uploads", patrao.token(), Map.of("kind", "EXTRATO",
                "title", "Extrato " + MONTH, "competence", MONTH, "request_id", req.json().path("id").asString()),
                "extrato.pdf", "application/pdf", PDF);
        assertThat(up.status()).as(up.body()).isEqualTo(201);
        String did = up.json().path("id").asString();
        JsonNode reqs = api.get("/api/v1/document-requests", contador.token()).json().path("items");
        assertThat(reqs.get(0).path("state").asString()).isEqualTo("FULFILLED");
        assertThat(reqs.get(0).path("fulfilled_by_name").asString()).isEqualTo("Patrão");
        // Nova versão exige motivo; a anterior continua guardada e baixável.
        assertThat(api.uploadWith("/api/v1/documents/" + did + "/versions", contador.token(), Map.of("reason", " "),
                "extrato2.png", "image/png", PNG).status()).isEqualTo(422);
        JsonNode v2 = api.uploadWith("/api/v1/documents/" + did + "/versions", contador.token(), Map.of("reason", "versão legível"),
                "extrato2.png", "image/png", PNG).json();
        assertThat(v2.path("current_version").asInt()).isEqualTo(2);
        assertThat(v2.path("versions")).hasSize(2);
        String v1file = v2.path("versions").get(1).path("file").path("id").asString();
        Api.Res dl = api.getBytes("/api/v1/documents/" + v1file + "/download", contador.token());
        assertThat(dl.status()).isEqualTo(200);
        assertThat(dl.body().getBytes(StandardCharsets.ISO_8859_1)).isEqualTo(PDF);
        // Sem `documents.view`: nem lista nem baixa.
        assertThat(api.get("/api/v1/documents", balcao.token()).status()).isEqualTo(403);
        assertThat(api.getBytes("/api/v1/documents/" + v1file + "/download", balcao.token()).status()).isEqualTo(403);
        int downloads = jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'documents.download' AND actor_user_id = :u")
                .param("u", contador.userId()).query(Integer.class).single();
        assertThat(downloads).isEqualTo(1);
    }
}
