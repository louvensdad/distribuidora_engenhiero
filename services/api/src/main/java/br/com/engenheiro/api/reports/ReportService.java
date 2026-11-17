package br.com.engenheiro.api.reports;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.finance.FinanceReportService;
import br.com.engenheiro.api.finance.FinanceReportService.Item;
import br.com.engenheiro.api.finance.FinanceReportService.Sub;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agregação canônica dos relatórios (F09, REP-01..06): um único cálculo por período civil em America/Sao_Paulo
 * (início inclusivo, fim exclusivo), por competência, com a unidade permitida pelo escopo. Tela, gráfico (colunas e linha
 * do tempo), comparação, metas e arquivos (CSV/XLSX/PDF) usam este mesmo objeto, já com os campos ocultos removidos —
 * nada é recalculado no cliente nem no exportador.
 */
@Service
public class ReportService {
    public static final String TIMEZONE = "America/Sao_Paulo";
    private static final Locale BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", BR);
    private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("MMM/yy", BR);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final FinanceReportService finance;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public ReportService(JdbcClient jdbc, AuthorizationService authz, FinanceReportService finance, AuditService audit,
                         JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.finance = finance;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------- contrato ----------------
    public record Filter(String period, LocalDate date, LocalDate from, LocalDate to, UUID branchId) {
    }

    public record Range(String period, LocalDate from, LocalDate to, String label) {
        long days() {
            return ChronoUnit.DAYS.between(from, to);
        }
    }

    public record Result(String gross_revenue, String discounts, String returns, String net_revenue, String cogs,
                         String expenses_total, String losses, String costs_expenses_losses, String profit,
                         String margin_percent, String outcome) {
    }

    public record Column(String key, String label, String amount) {
    }

    public record Point(LocalDate from, LocalDate to, String label, String net_revenue, String expenses_total, String cogs,
                        String losses, String profit, String sold_quantity) {
    }

    public record UnitQty(String unit, String sold, String backlog) {
    }

    public record Sold(String quantity_total, String unit, List<UnitQty> by_unit, int orders, String note) {
    }

    public record Delta(String key, String label, String current, String previous, String difference, String percent) {
    }

    public record Comparison(LocalDate from, LocalDate to, String label, List<Delta> items, String formula) {
    }

    public record Closed(OffsetDateTime closed_at, String closed_by_name, String policy_version, boolean matches_live) {
    }

    public record Report(String policy_version, String timezone, String period, LocalDate from, LocalDate to, String label,
                         UUID branch_id, String branch_name, String basis, String source, Closed closed,
                         List<String> hidden, Result result, List<Item> expenses, List<Sub> subcategories, Sold sold,
                         String chart_unit, List<Column> chart, String timeline_bucket, List<Point> timeline,
                         Comparison comparison, List<Item> drill, FinanceReportService.CashPosition cash, String formula,
                         String note, String generated_by, OffsetDateTime generated_at) {
    }

    /** Escopo efetivo do relatório: unidade (null = empresa toda) e se o ator vê o fechamento. */
    public record Access(UUID branch, String branchName, boolean closing, boolean organization) {
    }

    // ---------------- período e escopo ----------------
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), Journal.COMMERCIAL);
    }

    /** Dia/semana (segunda a domingo)/mês civis em São Paulo, ou intervalo livre [from, to) de até 3 anos. */
    public Range range(Filter f) {
        String p = f.period() == null || f.period().isBlank() ? "MONTH" : f.period().trim().toUpperCase(Locale.ROOT);
        LocalDate d = f.date() == null ? today() : f.date();
        return switch (p) {
            case "DAY" -> new Range(p, d, d.plusDays(1), DMY.format(d));
            case "WEEK" -> {
                LocalDate s = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Range(p, s, s.plusDays(7), "Semana de " + DM.format(s) + " a " + DMY.format(s.plusDays(6)));
            }
            case "MONTH" -> {
                LocalDate s = d.withDayOfMonth(1);
                yield new Range(p, s, s.plusMonths(1), MONTH.format(s));
            }
            case "CUSTOM" -> {
                if (f.from() == null || f.to() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "Intervalo livre exige início e fim.");
                }
                LocalDate[] r = FinanceReportService.range(f.from(), f.to());
                yield new Range(p, r[0], r[1], DMY.format(r[0]) + " a " + DMY.format(r[1].minusDays(1)));
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "Período: DAY, WEEK, MONTH ou CUSTOM.");
        };
    }

    /**
     * `reports.view` na organização escolhe qualquer unidade (ou todas); na unidade, fica preso à própria — pedir outra
     * responde 404 (indistinguível). Outros escopos (próprio, atribuído, setor) não abrem relatório consolidado.
     */
    public Access access(Actor actor, String resource, String action, UUID requested) {
        Decision d = authz.require(actor, resource, action);
        UUID branch;
        if (d.scopes().contains(Scope.ORGANIZATION)) {
            branch = requested;
        } else if (d.scopes().contains(Scope.BRANCH)) {
            if (requested != null && !requested.equals(actor.branchId())) {
                audit.recordIndependently(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                        actor.clientId(), "access.denied", "branch", requested.toString(), "DENIED", "unidade fora do escopo",
                        Map.of("capability", resource + "." + action), "api"));
                throw ApiException.notFound();
            }
            branch = actor.branchId();
        } else {
            throw ApiException.forbidden();
        }
        String name = null;
        if (branch != null) {
            name = jdbc.sql("SELECT name FROM branches WHERE id = :b AND organization_id = :o").param("b", branch)
                    .param("o", actor.organizationId()).query(String.class).optional().orElseThrow(ApiException::notFound);
        }
        return new Access(branch, name, finance.closingVisible(actor), branch == null);
    }

    // ---------------- agregação ----------------
    /** Saldos por dia civil de competência e conta, e quantidades vendidas por dia e unidade — base de tudo. */
    record Daily(Map<LocalDate, Map<String, BigDecimal>> balances, Map<LocalDate, Map<String, BigDecimal>> sold, int orders) {
        Map<String, BigDecimal> total(LocalDate f, LocalDate t) {
            Map<String, BigDecimal> m = new TreeMap<>();
            balances.forEach((d, accs) -> {
                if (!d.isBefore(f) && d.isBefore(t)) {
                    accs.forEach((a, v) -> m.merge(a, v, BigDecimal::add));
                }
            });
            return m;
        }

        Map<String, BigDecimal> soldTotal(LocalDate f, LocalDate t) {
            Map<String, BigDecimal> m = new TreeMap<>();
            sold.forEach((d, units) -> {
                if (!d.isBefore(f) && d.isBefore(t)) {
                    units.forEach((u, v) -> m.merge(u, v, BigDecimal::add));
                }
            });
            return m;
        }
    }

    Daily daily(UUID org, LocalDate f, LocalDate t, UUID branch) {
        record Row(LocalDate d, String account, BigDecimal v) {
        }
        Map<LocalDate, Map<String, BigDecimal>> bal = new TreeMap<>();
        for (Row r : jdbc.sql("""
                        SELECT coalesce(e.competence, e.entry_date) AS d, l.account, sum(l.debit - l.credit) AS v
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND coalesce(e.competence, e.entry_date) >= :f
                           AND coalesce(e.competence, e.entry_date) < :t
                           AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))
                         GROUP BY 1, 2
                        """).param("o", org).param("f", f).param("t", t).param("b", branch).query(Row.class).list()) {
            bal.computeIfAbsent(r.d(), k -> new TreeMap<>()).put(r.account(), r.v());
        }
        record Q(LocalDate d, String unit, BigDecimal q) {
        }
        Map<LocalDate, Map<String, BigDecimal>> sold = new TreeMap<>();
        for (Q q : jdbc.sql(SOLD + " GROUP BY 1, 2").param("o", org).param("f", f).param("t", t).param("b", branch)
                .query(Q.class).list()) {
            sold.computeIfAbsent(q.d(), k -> new TreeMap<>()).put(q.unit(), q.q());
        }
        int orders = jdbc.sql("""
                        SELECT CAST(count(DISTINCT f.order_id) AS int) FROM journal_entries e JOIN fulfillments f ON f.id = e.source_id
                         WHERE e.organization_id = :o AND e.source_type = 'order_fulfillment'
                           AND coalesce(e.competence, e.entry_date) >= :f AND coalesce(e.competence, e.entry_date) < :t
                           AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))
                        """).param("o", org).param("f", f).param("t", t).param("b", branch).query(Integer.class).single();
        return new Daily(bal, sold, orders);
    }

    /** Quantidade efetivamente vendida (cumprida − devolvida), na data do lançamento da venda — a mesma da receita. */
    static final String SOLD = """
            SELECT coalesce(e.competence, e.entry_date) AS d, p.base_unit AS unit,
                   sum(CASE WHEN f.kind = 'FULFILLMENT' THEN fl.base_quantity ELSE -fl.base_quantity END) AS q
              FROM journal_entries e JOIN fulfillments f ON f.id = e.source_id
              JOIN fulfillment_lines fl ON fl.fulfillment_id = f.id
              JOIN order_lines ol ON ol.id = fl.order_line_id JOIN products p ON p.id = ol.product_id
             WHERE e.organization_id = :o AND e.source_type IN ('order_fulfillment', 'order_return')
               AND coalesce(e.competence, e.entry_date) >= :f AND coalesce(e.competence, e.entry_date) < :t
               AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))
            """;

    /** Resultado gerencial a partir dos saldos do livro (mesmas contas de {@link FinanceReportService}). */
    static FinanceReportService.Result result(Map<String, BigDecimal> bal) {
        Map<String, BigDecimal> exp = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> c : FinanceReportService.EXPENSE_CATEGORIES.entrySet()) {
            exp.put(c.getKey(), sum(bal, c.getValue()));
        }
        return FinanceReportService.compute(bal.getOrDefault("RECEITA_VENDAS", BigDecimal.ZERO).negate(),
                bal.getOrDefault("DESCONTOS_CONCEDIDOS", BigDecimal.ZERO), bal.getOrDefault("DEVOLUCOES_VENDAS", BigDecimal.ZERO),
                bal.getOrDefault("CMV", BigDecimal.ZERO), exp, sum(bal, List.of("PERDAS_ESTOQUE", "AJUSTES_ESTOQUE")));
    }

    static BigDecimal sum(Map<String, BigDecimal> bal, List<String> accounts) {
        return accounts.stream().map(a -> bal.getOrDefault(a, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Valores do período para metas e comparações (sem montar o relatório inteiro). */
    public record Totals(BigDecimal netRevenue, BigDecimal expenses, BigDecimal profit, Map<String, BigDecimal> soldByUnit) {
    }

    public Totals totals(UUID org, LocalDate f, LocalDate t, UUID branch) {
        Daily d = daily(org, f, t, branch);
        FinanceReportService.Result r = result(d.total(f, t));
        return new Totals(r.netRevenue(), r.expensesTotal(), r.profit(), d.soldTotal(f, t));
    }

    // ---------------- relatório ----------------
    @Transactional // a posição de caixa garante as contas padrão (pode inserir na primeira vez)
    public Report report(Actor actor, Filter filter) {
        Access a = access(actor, "reports", "view", filter.branchId());
        return build(actor, a, range(filter));
    }

    Report build(Actor actor, Access a, Range r) {
        UUID org = actor.organizationId();
        Daily daily = daily(org, r.from(), r.to(), a.branch());
        Map<String, BigDecimal> bal = daily.total(r.from(), r.to());
        FinanceReportService.Result live = result(bal);
        boolean closing = a.closing();

        // Mês fechado (empresa toda): o resultado vem do retrato gravado com a política da época; conferido com o livro.
        Closed closed = null;
        FinanceReportService.Result shown = live;
        String source = "LIVE";
        if (r.period().equals("MONTH") && a.organization()) {
            Optional<Snapshot> snap = snapshot(org, r.from());
            if (snap.isPresent()) {
                Snapshot s = snap.get();
                shown = s.result();
                source = "SNAPSHOT";
                boolean same = s.result().profit().compareTo(live.profit()) == 0
                        && s.result().netRevenue().compareTo(live.netRevenue()) == 0
                        && s.result().expensesTotal().compareTo(live.expensesTotal()) == 0;
                closed = new Closed(s.closedAt(), s.closedBy(), s.policy(), same);
            }
        }
        List<String> hidden = closing ? List.of() : FinanceReportService.CLOSING_FIELDS;
        Result res = new Result(m(shown.grossRevenue()), m(shown.discounts()), m(shown.returns()), m(shown.netRevenue()),
                closing ? m(shown.cogs()) : null, m(shown.expensesTotal()), closing ? m(shown.losses()) : null,
                closing ? m(shown.costsExpensesLosses()) : null, closing ? m(shown.profit()) : null,
                closing && shown.marginPercent() != null ? shown.marginPercent().toPlainString() : null,
                closing ? outcome(shown.profit()) : null);

        List<Item> expenses = new ArrayList<>();
        for (Map.Entry<String, List<String>> c : FinanceReportService.EXPENSE_CATEGORIES.entrySet()) {
            expenses.add(new Item(c.getKey(), FinanceReportService.label(c.getKey()), m(shown.expenses().get(c.getKey())), c.getValue()));
        }
        List<Sub> subs = finance.subcategories(org, r.from(), r.to(), a.branch());

        // Colunas do gráfico = exatamente as linhas do fechamento (mesmas strings); ocultas não entram.
        List<Column> chart = new ArrayList<>();
        chart.add(new Column("net_revenue", "Vendas (receita líquida)", res.net_revenue()));
        if (closing) {
            chart.add(new Column("cogs", "CMV", res.cogs()));
        }
        chart.add(new Column("expenses_total", "Despesas", res.expenses_total()));
        if (closing) {
            chart.add(new Column("losses", "Perdas", res.losses()));
            chart.add(new Column("profit", shown.profit().signum() < 0 ? "Prejuízo" : "Lucro", res.profit()));
        }

        String bucket = r.days() <= 62 ? "DAY" : r.days() <= 186 ? "WEEK" : "MONTH";
        List<Point> timeline = new ArrayList<>();
        for (LocalDate[] b : buckets(r, bucket)) {
            FinanceReportService.Result pr = result(daily.total(b[0], b[1]));
            Map<String, BigDecimal> q = daily.soldTotal(b[0], b[1]);
            timeline.add(new Point(b[0], b[1], bucketLabel(bucket, b[0], b[1]), m(pr.netRevenue()), m(pr.expensesTotal()),
                    closing ? m(pr.cogs()) : null, closing ? m(pr.losses()) : null, closing ? m(pr.profit()) : null,
                    q.size() == 1 ? qty(q.values().iterator().next()) : null));
        }

        Sold sold = sold(org, daily.soldTotal(r.from(), r.to()), daily.orders(), a.branch());
        Comparison comparison = compare(org, r, a, live, sold);
        List<Item> drill = new ArrayList<>();
        drill.add(new Item("gross_revenue", "Vendas cumpridas (bruto)", res.gross_revenue(), List.of("RECEITA_VENDAS")));
        drill.add(new Item("discounts", "Descontos concedidos", res.discounts(), List.of("DESCONTOS_CONCEDIDOS")));
        drill.add(new Item("returns", "Devoluções de vendas", res.returns(), List.of("DEVOLUCOES_VENDAS")));
        if (closing) {
            drill.add(new Item("cogs", "Custo da mercadoria vendida (CMV)", res.cogs(), List.of("CMV")));
            drill.add(new Item("losses", "Perdas e ajustes de estoque", res.losses(), List.of("PERDAS_ESTOQUE", "AJUSTES_ESTOQUE")));
        }
        FinanceReportService.CashPosition cash = a.organization() ? finance.cash(org, r.to()) : null;
        String note = "Resultado gerencial por competência (" + FinanceReportService.POLICY_VERSION + "); não é a demonstração "
                + "contábil do contador. Saldo em caixa não é lucro."
                + (a.branch() != null ? " Filtrado pela unidade: só lançamentos registrados nela; posição de caixa da empresa não aparece." : "")
                + (closing ? "" : " CMV, perdas e lucro ocultos pela sua permissão (custo de compra e margem).");
        return new Report(FinanceReportService.POLICY_VERSION, TIMEZONE, r.period(), r.from(), r.to(), r.label(), a.branch(),
                a.branchName(), "COMPETENCE", source, closed, hidden, res, expenses, subs, sold, "BRL", chart, bucket, timeline,
                comparison, drill, cash,
                "Lucro = receita líquida − CMV − despesas − perdas; receita líquida = vendas cumpridas − descontos − devoluções; "
                        + "margem = lucro ÷ receita líquida × 100.",
                note, actor.displayName(), OffsetDateTime.now(clock));
    }

    Sold sold(UUID org, Map<String, BigDecimal> byUnit, int orders, UUID branch) {
        record B(String unit, BigDecimal q) {
        }
        Map<String, BigDecimal> backlog = new TreeMap<>();
        for (B b : jdbc.sql("""
                        SELECT p.base_unit AS unit, sum(ol.base_quantity - ol.fulfilled_base_qty - ol.cancelled_base_qty) AS q
                          FROM order_lines ol JOIN orders o ON o.id = ol.order_id JOIN products p ON p.id = ol.product_id
                         WHERE o.organization_id = :o AND o.state IN ('CONFIRMED', 'READY', 'PARTIALLY_FULFILLED')
                           AND (CAST(:b AS uuid) IS NULL OR o.branch_id = CAST(:b AS uuid))
                         GROUP BY 1 HAVING sum(ol.base_quantity - ol.fulfilled_base_qty - ol.cancelled_base_qty) > 0
                        """).param("o", org).param("b", branch).query(B.class).list()) {
            backlog.put(b.unit(), b.q());
        }
        java.util.Set<String> units = new java.util.TreeSet<>(byUnit.keySet());
        units.addAll(backlog.keySet());
        List<UnitQty> list = units.stream().map(u -> new UnitQty(u, qty(byUnit.getOrDefault(u, BigDecimal.ZERO)),
                qty(backlog.getOrDefault(u, BigDecimal.ZERO)))).toList();
        boolean single = byUnit.size() <= 1;
        return new Sold(single ? qty(byUnit.values().stream().findFirst().orElse(BigDecimal.ZERO)) : null,
                byUnit.size() == 1 ? byUnit.keySet().iterator().next() : null, list, orders,
                single ? "Quantidade efetivamente retirada/entregue menos devoluções, em unidade-base. Em carteira = reservado, ainda não vendido."
                        : "Produtos com unidades-base diferentes: as quantidades não são somadas entre si.");
    }

    /** Período anterior equivalente (dia, semana, mês ou intervalo de mesmo tamanho imediatamente antes). */
    Comparison compare(UUID org, Range r, Access a, FinanceReportService.Result cur, Sold sold) {
        LocalDate pf;
        LocalDate pt = r.from();
        switch (r.period()) {
            case "MONTH" -> pf = r.from().minusMonths(1);
            case "WEEK" -> pf = r.from().minusDays(7);
            case "DAY" -> pf = r.from().minusDays(1);
            default -> pf = r.from().minusDays(r.days());
        }
        Daily pd = daily(org, pf, pt, a.branch());
        FinanceReportService.Result prev = result(pd.total(pf, pt));
        List<Delta> items = new ArrayList<>();
        items.add(delta("net_revenue", "Vendas (receita líquida)", cur.netRevenue(), prev.netRevenue(), 2));
        items.add(delta("expenses_total", "Despesas", cur.expensesTotal(), prev.expensesTotal(), 2));
        if (a.closing()) {
            items.add(delta("profit", "Lucro/prejuízo", cur.profit(), prev.profit(), 2));
        }
        Map<String, BigDecimal> pq = pd.soldTotal(pf, pt);
        if (sold.unit() != null && (pq.isEmpty() || (pq.size() == 1 && pq.containsKey(sold.unit())))) {
            items.add(delta("sold_quantity", "Quantidade vendida (" + sold.unit() + ")", new BigDecimal(sold.quantity_total()),
                    pq.getOrDefault(sold.unit(), BigDecimal.ZERO), 6));
        }
        String label = r.period().equals("MONTH") ? MONTH.format(pf) : r.period().equals("DAY") ? DMY.format(pf)
                : DMY.format(pf) + " a " + DMY.format(pt.minusDays(1));
        return new Comparison(pf, pt, label, items,
                "Diferença = atual − anterior; variação % = diferença ÷ |anterior| × 100 (sem % quando o anterior é zero).");
    }

    static Delta delta(String key, String label, BigDecimal cur, BigDecimal prev, int scale) {
        BigDecimal diff = cur.subtract(prev);
        String pct = prev.signum() == 0 ? null
                : diff.multiply(BigDecimal.valueOf(100)).divide(prev.abs(), 2, RoundingMode.HALF_UP).toPlainString();
        return new Delta(key, label, scale == 2 ? m(cur) : qty(cur), scale == 2 ? m(prev) : qty(prev),
                scale == 2 ? m(diff) : qty(diff), pct);
    }

    static List<LocalDate[]> buckets(Range r, String bucket) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate s = r.from();
        while (s.isBefore(r.to())) {
            LocalDate e = switch (bucket) {
                case "DAY" -> s.plusDays(1);
                case "WEEK" -> s.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                default -> s.withDayOfMonth(1).plusMonths(1);
            };
            if (e.isAfter(r.to())) {
                e = r.to();
            }
            out.add(new LocalDate[] {s, e});
            s = e;
        }
        return out;
    }

    static String bucketLabel(String bucket, LocalDate f, LocalDate t) {
        return switch (bucket) {
            case "DAY" -> DM.format(f);
            case "WEEK" -> DM.format(f) + "–" + DM.format(t.minusDays(1));
            default -> MON.format(f);
        };
    }

    // ---------------- retrato do período fechado ----------------
    record Snapshot(FinanceReportService.Result result, String policy, OffsetDateTime closedAt, String closedBy) {
    }

    Optional<Snapshot> snapshot(UUID org, LocalDate month) {
        record P(String snapshot, OffsetDateTime closedAt, String closedBy) {
        }
        return jdbc.sql("""
                        SELECT CAST(p.snapshot AS text) AS snapshot, p.closed_at, u.display_name AS closed_by
                          FROM fin_periods p LEFT JOIN app_users u ON u.id = p.closed_by
                         WHERE p.organization_id = :o AND p.month = :m AND p.state = 'CLOSED'
                        """).param("o", org).param("m", month).query(P.class).optional().map(p -> {
            JsonNode s = json.readTree(p.snapshot()).path("summary");
            Map<String, BigDecimal> exp = new LinkedHashMap<>();
            for (JsonNode e : s.path("expenses")) {
                exp.put(e.path("key").asString(), new BigDecimal(e.path("amount").asString()));
            }
            FinanceReportService.Result r = FinanceReportService.compute(dec(s, "gross_revenue"), dec(s, "discounts"),
                    dec(s, "returns"), dec(s, "cogs"), exp, dec(s, "losses"));
            String policy = s.path("policy_version").isString() ? s.path("policy_version").asString()
                    : FinanceReportService.POLICY_VERSION; // retratos anteriores à F09 usam a mesma regra (versão 1)
            return new Snapshot(r, policy, p.closedAt(), p.closedBy());
        });
    }

    static BigDecimal dec(JsonNode n, String f) {
        return n.path(f).isString() ? new BigDecimal(n.path(f).asString()) : BigDecimal.ZERO;
    }

    // ---------------- arquivos ----------------
    public record FileOut(String name, String contentType, byte[] body) {
    }

    /**
     * CSV/XLSX exigem `reports.export`; PDF (impressão) exige `reports.print`; ambos sobre o MESMO relatório que a tela
     * mostraria a esta pessoa (`reports.view` e escopo). Cada arquivo gerado fica na auditoria.
     */
    @Transactional
    public FileOut export(Actor actor, Filter filter, String format, ReportExporter exporter) {
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        String action = switch (fmt) {
            case "csv", "xlsx" -> "export";
            case "pdf" -> "print";
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "Formato: csv, xlsx ou pdf.");
        };
        authz.require(actor, "reports", action);
        Report r = report(actor, filter);
        byte[] body = switch (fmt) {
            case "csv" -> exporter.csv(r);
            case "xlsx" -> exporter.xlsx(r);
            default -> exporter.pdf(r);
        };
        String name = "relatorio-" + r.from() + "_" + r.to().minusDays(1) + "." + fmt;
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("format", fmt);
        changes.put("from", r.from().toString());
        changes.put("to", r.to().toString());
        changes.put("branch", r.branch_id() == null ? "todas" : r.branch_id().toString());
        changes.put("hidden", String.join(",", r.hidden()));
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(),
                "reports." + action, "report", null, "SUCCESS", null, changes, "api"));
        String type = switch (fmt) {
            case "csv" -> "text/csv; charset=UTF-8";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/pdf";
        };
        return new FileOut(name, type, body);
    }

    // ---------------- drilldown ----------------
    @Transactional(readOnly = true)
    public FinanceReportService.Lines lines(Actor actor, List<String> accounts, Filter filter) {
        Access a = access(actor, "reports", "view", filter.branchId());
        Range r = range(filter);
        return finance.linesUnchecked(actor, accounts, r.from(), r.to(), null, null, a.branch());
    }

    // ---------------- apoio ----------------
    static String m(BigDecimal v) {
        return v == null ? null : Decimals.moneyStr(v);
    }

    static String qty(BigDecimal v) {
        return v.setScale(Decimals.QTY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    static String outcome(BigDecimal profit) {
        return profit.signum() > 0 ? "PROFIT" : profit.signum() < 0 ? "LOSS" : "ZERO";
    }
}
