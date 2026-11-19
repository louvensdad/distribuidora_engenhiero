package br.com.engenheiro.api.ai;

import br.com.engenheiro.api.accounting.AccountingDocumentService;
import br.com.engenheiro.api.accounting.PayrollService;
import br.com.engenheiro.api.accounting.TaxObligationService;
import br.com.engenheiro.api.finance.FinanceReportService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.inventory.LossService;
import br.com.engenheiro.api.inventory.StockQueryService;
import br.com.engenheiro.api.logistics.VehicleExpenseService;
import br.com.engenheiro.api.logistics.VehicleService;
import br.com.engenheiro.api.reports.GoalService;
import br.com.engenheiro.api.reports.ReportService;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ferramentas SOMENTE LEITURA oferecidas ao modelo (arquitetura/03): cada uma chama o serviço que o site usa, com a
 * identidade autenticada — organização, unidade e permissões vêm do {@link Actor}, nunca da entrada do modelo. O que
 * o ator não pode ver volta como SEM_PERMISSAO e vira limitação declarada. Valores são os calculados pelo sistema
 * (texto decimal exato); o modelo só explica. Não existe ferramenta que escreva, pague, emita ou conceda acesso.
 */
@Component
public class AiTools {
    static final int MAX_ITEMS = 50;

    private final ReportService reports;
    private final FinanceReportService finance;
    private final VehicleService vehicles;
    private final VehicleExpenseService vehicleExpenses;
    private final TaxObligationService taxes;
    private final PayrollService payroll;
    private final AccountingDocumentService documents;
    private final StockQueryService stock;
    private final LossService losses;
    private final GoalService goals;
    private final JsonMapper json;

    public AiTools(ReportService reports, FinanceReportService finance, VehicleService vehicles,
                   VehicleExpenseService vehicleExpenses, TaxObligationService taxes, PayrollService payroll,
                   AccountingDocumentService documents, StockQueryService stock, LossService losses, GoalService goals,
                   JsonMapper json) {
        this.reports = reports;
        this.finance = finance;
        this.vehicles = vehicles;
        this.vehicleExpenses = vehicleExpenses;
        this.taxes = taxes;
        this.payroll = payroll;
        this.documents = documents;
        this.stock = stock;
        this.losses = losses;
        this.goals = goals;
        this.json = json;
    }

    /** Link autorizado para a tela do site (o app abre o módulo correspondente). */
    public record Source(String tool, String label, String href) {
    }

    /** Resultado de uma ferramenta: conteúdo JSON para o modelo, fontes e limitações calculadas pelo sistema. */
    public record Outcome(String content, boolean error, List<Source> sources, List<String> limitations) {
    }

    /** Período padrão da pergunta, [from, to). */
    public record Period(LocalDate from, LocalDate to) {
    }

    private static final Map<String, Object> RANGE_PROPS = Map.of(
            "inicio", Map.of("type", "string", "description", "Primeiro dia (AAAA-MM-DD). Omitido: período da pergunta."),
            "fim", Map.of("type", "string", "description", "Último dia, inclusive (AAAA-MM-DD). Omitido: período da pergunta."));

    public List<LlmProvider.ToolSpec> specs() {
        return List.of(
                spec("consultar_resumo", "Resultado do período calculado pelo relatório canônico: receita líquida, CMV, "
                        + "despesas, perdas, lucro, margem, quantidade vendida, caixa e comparação com o período anterior.",
                        RANGE_PROPS, List.of()),
                spec("listar_despesas_por_categoria", "Despesas do período por categoria e subcategoria (regime de "
                        + "competência), com o total.", RANGE_PROPS, List.of()),
                spec("detalhar_veiculo", "Dados de um veículo pela placa (situação, hodômetro, revisão, documentos) e "
                        + "seus gastos (combustível e manutenção) no período.",
                        merge(RANGE_PROPS, Map.of("placa", Map.of("type", "string", "description", "Placa, ex.: ABC1D23"))),
                        List.of("placa")),
                spec("consultar_pendencias_contador", "Guias, folhas e solicitações de documentos que estão com o "
                        + "contador ou esperando decisão do dono.", Map.of(), List.of()),
                spec("consultar_estoque_perdas", "Saldo de estoque por unidade (físico, reservado, disponível, perdido) "
                        + "e perdas registradas no período.", RANGE_PROPS, List.of()),
                spec("comparar_periodos", "Compara o resultado de dois períodos (A e B) com a diferença calculada pelo "
                        + "sistema.", Map.of(
                        "a_inicio", Map.of("type", "string", "description", "Período A: primeiro dia (AAAA-MM-DD)"),
                        "a_fim", Map.of("type", "string", "description", "Período A: último dia, inclusive"),
                        "b_inicio", Map.of("type", "string", "description", "Período B: primeiro dia (AAAA-MM-DD)"),
                        "b_fim", Map.of("type", "string", "description", "Período B: último dia, inclusive")),
                        List.of("a_inicio", "a_fim", "b_inicio", "b_fim")),
                spec("consultar_meta", "Metas do período (alvo, situação e medida) para comparar com o realizado.",
                        RANGE_PROPS, List.of()));
    }

    private static LlmProvider.ToolSpec spec(String name, String description, Map<String, Object> props,
                                             List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        return new LlmProvider.ToolSpec(name, description, schema);
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> m = new LinkedHashMap<>(a);
        m.putAll(b);
        return m;
    }

    /** Executa a ferramenta pedida. Nome fora do catálogo é recusado (nada é executado). */
    public Outcome run(Actor actor, String name, Map<String, Object> input, Period period) {
        Map<String, Object> in = input == null ? Map.of() : input;
        try {
            return switch (name) {
                case "consultar_resumo" -> summary(actor, range(in, "inicio", "fim", period));
                case "listar_despesas_por_categoria" -> categories(actor, range(in, "inicio", "fim", period));
                case "detalhar_veiculo" -> vehicle(actor, str(in.get("placa")), range(in, "inicio", "fim", period));
                case "consultar_pendencias_contador" -> accountantPending(actor);
                case "consultar_estoque_perdas" -> stockLosses(actor, range(in, "inicio", "fim", period));
                case "comparar_periodos" -> compare(actor, range(in, "a_inicio", "a_fim", null),
                        range(in, "b_inicio", "b_fim", null));
                case "consultar_meta" -> goalsOf(actor, range(in, "inicio", "fim", period));
                default -> new Outcome("{\"erro\":\"FERRAMENTA_INEXISTENTE\",\"detalhe\":\"Só existem ferramentas de "
                        + "consulta; nenhuma ação foi executada.\"}", true, List.of(), List.of());
            };
        } catch (ApiException e) {
            if (e.code() == ErrorCode.FORBIDDEN) {
                return new Outcome("{\"erro\":\"SEM_PERMISSAO\",\"detalhe\":\"O usuário não tem acesso a estes dados.\"}",
                        true, List.of(), List.of("Sem permissão para os dados de \"" + label(name) + "\"."));
            }
            return new Outcome(write(Map.of("erro", e.code().name(), "detalhe", Objects.toString(e.getMessage(), ""))),
                    true, List.of(), List.of());
        }
    }

    static String label(String tool) {
        return switch (tool) {
            case "consultar_resumo" -> "resultado do período";
            case "listar_despesas_por_categoria" -> "despesas por categoria";
            case "detalhar_veiculo" -> "veículo";
            case "consultar_pendencias_contador" -> "pendências do contador";
            case "consultar_estoque_perdas" -> "estoque e perdas";
            case "comparar_periodos" -> "comparação de períodos";
            case "consultar_meta" -> "metas";
            default -> tool;
        };
    }

    // ---------------- ferramentas ----------------

    private Outcome summary(Actor actor, Period p) {
        ReportService.Report r = reports.report(actor, new ReportService.Filter("CUSTOM", null, p.from(), p.to(), null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", periodMap(r.from(), r.to(), r.label()));
        out.put("unidade", r.branch_name() == null ? "Empresa toda" : r.branch_name());
        out.put("base", r.basis());
        out.put("fechado", r.closed() != null);
        out.put("resultado", r.result());
        out.put("despesas_por_categoria", r.expenses());
        out.put("vendido", r.sold());
        out.put("caixa", r.cash());
        out.put("comparacao_periodo_anterior", r.comparison());
        out.put("campos_ocultos_para_o_usuario", r.hidden());
        out.put("formula", r.formula());
        List<String> lim = new ArrayList<>();
        if (r.hidden() != null && !r.hidden().isEmpty()) {
            lim.add("Campos ocultos pela permissão do usuário: " + String.join(", ", r.hidden()) + ".");
        }
        if (r.sold() != null && r.sold().orders() == 0) {
            lim.add("Nenhuma venda concluída no período " + r.label() + ".");
        }
        return new Outcome(write(out), false, List.of(new Source("consultar_resumo",
                "Relatório " + r.label(), "/reports")), lim);
    }

    private Outcome categories(Actor actor, Period p) {
        FinanceReportService.Categories c = finance.categories(actor, p.from(), p.to());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", periodMap(c.from(), c.to(), null));
        out.put("categorias", c.categories());
        out.put("subcategorias", c.subcategories().stream().limit(MAX_ITEMS).toList());
        out.put("total", c.total());
        List<String> lim = c.categories().isEmpty() ? List.of("Nenhuma despesa lançada no período.") : List.of();
        return new Outcome(write(out), false, List.of(new Source("listar_despesas_por_categoria",
                "Despesas por categoria", "/reports/categories")), lim);
    }

    private Outcome vehicle(Actor actor, String plate, Period p) {
        String wanted = plate == null ? "" : plate.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return new Outcome("{\"erro\":\"PLACA_OBRIGATORIA\"}", true, List.of(), List.of());
        }
        VehicleService.VehicleView v = vehicles.list(actor, null).items().stream()
                .filter(x -> x.plate() != null && x.plate().replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(wanted))
                .findFirst().orElse(null);
        if (v == null) {
            return new Outcome("{\"erro\":\"VEICULO_NAO_ENCONTRADO\"}", true, List.of(),
                    List.of("Nenhum veículo visível ao usuário com a placa " + wanted + "."));
        }
        List<String> lim = new ArrayList<>();
        List<Map<String, Object>> spent = new ArrayList<>();
        BigDecimal fuel = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        try {
            for (VehicleExpenseService.ExpenseView e : vehicleExpenses.list(actor, v.id(), null, null)) {
                if (e.occurred_on() == null || e.occurred_on().isBefore(p.from()) || !e.occurred_on().isBefore(p.to())
                        || "REJECTED".equals(e.state())) {
                    continue;
                }
                if (e.amount() != null) {
                    if ("FUEL".equals(e.kind())) {
                        fuel = fuel.add(new BigDecimal(e.amount()));
                    } else {
                        other = other.add(new BigDecimal(e.amount()));
                    }
                }
                if (spent.size() < MAX_ITEMS) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("data", e.occurred_on());
                    m.put("tipo", e.kind());
                    m.put("valor", e.amount());
                    m.put("situacao", e.state());
                    m.put("litros", e.liters());
                    m.put("hodometro", e.odometer());
                    m.put("viagem", e.trip_number());
                    spent.add(m);
                }
            }
        } catch (ApiException e) {
            if (e.code() != ErrorCode.FORBIDDEN) {
                throw e;
            }
            lim.add("Sem permissão para os gastos do veículo; mostrados só os dados cadastrais.");
            spent = null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", periodMap(p.from(), p.to(), null));
        out.put("veiculo", Map.of("placa", v.plate(), "modelo", Objects.toString(v.model(), ""), "situacao", v.status(),
                "hodometro_km", Objects.toString(v.odometer_km(), ""), "proxima_revisao_km",
                Objects.toString(v.next_service_km(), ""), "licenciamento_ate", Objects.toString(v.registration_valid_until(), ""),
                "seguro_ate", Objects.toString(v.insurance_valid_until(), "")));
        if (spent != null) {
            out.put("gastos", spent);
            out.put("total_combustivel", fuel.toPlainString());
            out.put("total_outros_gastos", other.toPlainString());
            if (spent.isEmpty()) {
                lim.add("Nenhum gasto do veículo " + v.plate() + " no período.");
            }
        }
        return new Outcome(write(out), false, List.of(new Source("detalhar_veiculo", "Veículo " + v.plate(),
                "/operation/fleet/" + v.id())), lim);
    }

    private Outcome accountantPending(Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Source> sources = new ArrayList<>();
        List<String> lim = new ArrayList<>();
        List<String> open = List.of("DRAFT", "SUBMITTED", "RETURNED", "APPROVED");
        try {
            out.put("guias", taxes.list(actor, null).stream().filter(t -> open.contains(t.state())).limit(MAX_ITEMS)
                    .map(t -> mapOf("tipo", t.kind(), "descricao", t.description(), "competencia", t.competence(),
                            "vencimento", t.due_date(), "valor", t.amount(), "situacao", t.state())).toList());
            sources.add(new Source("consultar_pendencias_contador", "Guias e impostos", "/finance/taxes"));
        } catch (ApiException e) {
            forbiddenOnly(e, lim, "guias");
        }
        try {
            // Só a situação da folha: linhas por pessoa (salário) nunca vão para o modelo.
            out.put("folhas", payroll.list(actor).stream().filter(r -> open.contains(r.state())).limit(MAX_ITEMS)
                    .map(r -> mapOf("competencia", r.competence(), "descricao", r.description(), "pagamento", r.pay_date(),
                            "situacao", r.state(), "observacao_da_decisao", r.decided_note())).toList());
            sources.add(new Source("consultar_pendencias_contador", "Folha de pagamento", "/finance/payroll"));
        } catch (ApiException e) {
            forbiddenOnly(e, lim, "folha");
        }
        try {
            out.put("solicitacoes_de_documento", documents.requests(actor, "OPEN").stream().limit(MAX_ITEMS)
                    .map(d -> mapOf("titulo", d.title(), "tipo", d.kind(), "competencia", d.competence(),
                            "prazo", d.due_date(), "pedido_a", d.requested_from_name())).toList());
            sources.add(new Source("consultar_pendencias_contador", "Documentos e solicitações", "/finance/documents"));
        } catch (ApiException e) {
            forbiddenOnly(e, lim, "documentos");
        }
        if (sources.isEmpty()) {
            return new Outcome("{\"erro\":\"SEM_PERMISSAO\"}", true, List.of(), lim);
        }
        return new Outcome(write(out), false, sources, lim);
    }

    private Outcome stockLosses(Actor actor, Period p) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Source> sources = new ArrayList<>();
        List<String> lim = new ArrayList<>();
        out.put("periodo", periodMap(p.from(), p.to(), null));
        try {
            out.put("saldo_por_unidade", stock.summary(actor).totals());
            sources.add(new Source("consultar_estoque_perdas", "Estoque", "/operation/stock"));
        } catch (ApiException e) {
            forbiddenOnly(e, lim, "estoque");
        }
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                LossService.Page page = losses.list(actor, null, 100, cursor);
                for (LossService.LossView l : page.items()) {
                    LocalDate d = l.created_at().atZoneSameInstant(br.com.engenheiro.api.finance.Journal.COMMERCIAL)
                            .toLocalDate();
                    if (!d.isBefore(p.from()) && d.isBefore(p.to()) && items.size() < MAX_ITEMS) {
                        items.add(mapOf("data", d, "produto", l.product_name(), "quantidade", l.quantity(), "unidade",
                                l.unit(), "motivo", l.reason(), "situacao", l.status(), "custo", l.total_cost()));
                    }
                }
                cursor = page.next_cursor();
            } while (cursor != null && ++pages < 10);
            out.put("perdas", items);
            if (items.isEmpty()) {
                lim.add("Nenhuma perda registrada no período.");
            }
        } catch (ApiException e) {
            forbiddenOnly(e, lim, "perdas");
        }
        if (sources.isEmpty() && !out.containsKey("perdas")) {
            return new Outcome("{\"erro\":\"SEM_PERMISSAO\"}", true, List.of(), lim);
        }
        return new Outcome(write(out), false, sources, lim);
    }

    private Outcome compare(Actor actor, Period a, Period b) {
        ReportService.Result ra = reports.report(actor, new ReportService.Filter("CUSTOM", null, a.from(), a.to(), null)).result();
        ReportService.Report rb = reports.report(actor, new ReportService.Filter("CUSTOM", null, b.from(), b.to(), null));
        ReportService.Result rbr = rb.result();
        String[][] keys = {{"net_revenue", "Receita líquida"}, {"cogs", "CMV"}, {"expenses_total", "Despesas"},
                {"losses", "Perdas"}, {"profit", "Lucro"}};
        List<Map<String, Object>> diff = new ArrayList<>();
        for (String[] k : keys) {
            String va = field(ra, k[0]);
            String vb = field(rbr, k[0]);
            diff.add(mapOf("medida", k[1], "periodo_a", va, "periodo_b", vb, "diferenca_b_menos_a",
                    va == null || vb == null ? null : new BigDecimal(vb).subtract(new BigDecimal(va)).toPlainString()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo_a", periodMap(a.from(), a.to(), null));
        out.put("periodo_b", periodMap(b.from(), b.to(), null));
        out.put("comparacao", diff);
        out.put("campos_ocultos_para_o_usuario", rb.hidden());
        List<String> lim = rb.hidden() != null && !rb.hidden().isEmpty()
                ? List.of("Campos ocultos pela permissão do usuário: " + String.join(", ", rb.hidden()) + ".") : List.of();
        return new Outcome(write(out), false, List.of(new Source("comparar_periodos", "Relatório (comparação)", "/reports")), lim);
    }

    private static String field(ReportService.Result r, String key) {
        if (r == null) {
            return null;
        }
        return switch (key) {
            case "net_revenue" -> r.net_revenue();
            case "cogs" -> r.cogs();
            case "expenses_total" -> r.expenses_total();
            case "losses" -> r.losses();
            case "profit" -> r.profit();
            default -> null;
        };
    }

    private Outcome goalsOf(Actor actor, Period p) {
        List<GoalService.GoalView> list = goals.list(actor, p.from(), p.to(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", periodMap(p.from(), p.to(), null));
        out.put("metas", list.stream().limit(MAX_ITEMS).map(g -> mapOf("titulo", g.title(), "medida", g.metric_label(),
                "unidade_de_medida", g.unit(), "alvo", g.target(), "periodo", g.period_label(), "unidade",
                g.branch_name() == null ? "Empresa toda" : g.branch_name(), "situacao", g.state())).toList());
        List<String> lim = list.isEmpty() ? List.of("Nenhuma meta cadastrada para o período.") : List.of();
        return new Outcome(write(out), false, List.of(new Source("consultar_meta", "Metas", "/reports/goals")), lim);
    }

    // ---------------- apoio ----------------

    private static void forbiddenOnly(ApiException e, List<String> lim, String what) {
        if (e.code() != ErrorCode.FORBIDDEN) {
            throw e;
        }
        lim.add("Sem permissão para " + what + ".");
    }

    /** Intervalo [inicio, fim] inclusivo → [from, to). Sem datas: período da pergunta. Até 3 anos. */
    static Period range(Map<String, Object> in, String startKey, String endKey, Period fallback) {
        String s = str(in.get(startKey));
        String e = str(in.get(endKey));
        if ((s == null || e == null) && fallback != null) {
            return fallback;
        }
        if (s == null || e == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe " + startKey + " e " + endKey + " (AAAA-MM-DD).");
        }
        try {
            LocalDate from = LocalDate.parse(s);
            LocalDate to = LocalDate.parse(e).plusDays(1);
            if (!to.isAfter(from) || to.isAfter(from.plusYears(3))) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Período inválido (fim antes do início ou mais de 3 anos).");
            }
            return new Period(from, to);
        } catch (DateTimeParseException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Datas no formato AAAA-MM-DD.");
        }
    }

    private static Map<String, Object> periodMap(LocalDate from, LocalDate to, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inicio", from);
        m.put("fim_inclusivo", to == null ? null : to.minusDays(1));
        if (label != null) {
            m.put("rotulo", label);
        }
        return m;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    String write(Object o) {
        return json.writeValueAsString(o);
    }
}
