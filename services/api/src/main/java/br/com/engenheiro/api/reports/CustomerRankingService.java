package br.com.engenheiro.api.reports;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Melhor cliente" (REP-08, cerebro/03): medidas distintas e públicas, cada uma com sua fórmula — volume, receita,
 * margem, recorrência, pontualidade e devoluções. Ordena por UMA medida escolhida; não existe nota única nem peso
 * arbitrário. Margem só para quem vê o fechamento. Mesma base de datas do relatório (lançamento da venda, competência).
 */
@Service
public class CustomerRankingService {
    static final List<String> SORTS = List.of("net_revenue", "quantity", "margin", "orders", "active_weeks", "on_time_percent",
            "returns");

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final AuthorizationService authz;

    public CustomerRankingService(JdbcClient jdbc, ReportService reports, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.authz = authz;
    }

    public record Measure(String key, String label, String formula) {
    }

    public record CustomerRow(int position, UUID customer_id, String customer_name, String net_revenue, String quantity,
                              String margin, String margin_percent, int orders, int active_weeks, int returns,
                              int receivables_due, int receivables_on_time, String on_time_percent) {
    }

    public record Ranking(LocalDate from, LocalDate to, String label, UUID branch_id, String sort, String unit,
                          List<Measure> measures, List<String> hidden, List<CustomerRow> items, String note) {
    }

    record Raw(UUID customerId, String name, BigDecimal net, BigDecimal qty, BigDecimal cost, int orders, int weeks,
               int returns, int due, int onTime) {
    }

    @Transactional(readOnly = true)
    public Ranking customers(Actor actor, ReportService.Filter filter, String sortParam) {
        ReportService.Access a = reports.access(actor, "reports", "view", filter.branchId());
        ReportService.Range r = reports.range(filter);
        String sort = sortParam == null || sortParam.isBlank() ? "net_revenue" : sortParam.trim().toLowerCase(Locale.ROOT);
        if (!SORTS.contains(sort)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Ordenação: " + String.join(", ", SORTS) + ".");
        }
        if (sort.equals("margin") && !a.closing()) {
            throw ApiException.forbidden();
        }
        boolean names = authz.decide(actor, "customers", "view").allowed();
        List<String> units = jdbc.sql("SELECT DISTINCT d.unit FROM (" + ReportService.SOLD + " GROUP BY 1, 2) d")
                .param("o", actor.organizationId()).param("f", r.from()).param("t", r.to()).param("b", a.branch())
                .query(String.class).list();
        String unit = units.size() == 1 ? units.get(0) : null;
        List<Raw> raw = jdbc.sql("""
                        WITH s AS (
                          SELECT o.customer_id, f.order_id, f.kind, f.id AS fid, coalesce(e.competence, e.entry_date) AS d,
                                 fl.base_quantity, fl.net_revenue, fl.cost
                            FROM journal_entries e JOIN fulfillments f ON f.id = e.source_id
                            JOIN fulfillment_lines fl ON fl.fulfillment_id = f.id JOIN orders o ON o.id = f.order_id
                           WHERE e.organization_id = :o AND e.source_type IN ('order_fulfillment', 'order_return')
                             AND coalesce(e.competence, e.entry_date) >= :f AND coalesce(e.competence, e.entry_date) < :t
                             AND (CAST(:b AS uuid) IS NULL OR e.branch_id = CAST(:b AS uuid))),
                        agg AS (
                          SELECT customer_id,
                                 sum(net_revenue) AS net,
                                 sum(CASE WHEN kind = 'FULFILLMENT' THEN base_quantity ELSE -base_quantity END) AS qty,
                                 sum(cost) AS cost,
                                 CAST(count(DISTINCT order_id) FILTER (WHERE kind = 'FULFILLMENT') AS int) AS orders,
                                 CAST(count(DISTINCT date_trunc('week', d)) FILTER (WHERE kind = 'FULFILLMENT') AS int) AS weeks,
                                 CAST(count(DISTINCT fid) FILTER (WHERE kind = 'RETURN') AS int) AS returns
                            FROM s GROUP BY customer_id),
                        rec AS (
                          SELECT o.customer_id, CAST(count(*) AS int) AS due,
                                 CAST(count(*) FILTER (WHERE r.balance = 0 AND r.state = 'SETTLED' AND
                                       (SELECT max(x.received_on) FROM receivable_settlements x
                                         WHERE x.receivable_id = r.id AND x.state = 'CONFIRMED') <= r.due_date) AS int) AS on_time
                            FROM receivables r JOIN orders o ON o.id = r.order_id
                           WHERE r.organization_id = :o AND r.kind = 'CUSTOMER' AND r.state <> 'CANCELLED'
                             AND r.due_date >= :f AND r.due_date < :t AND r.due_date < :today
                             AND (CAST(:b AS uuid) IS NULL OR r.branch_id = CAST(:b AS uuid))
                           GROUP BY o.customer_id)
                        SELECT c.id AS customer_id, c.name AS name, agg.net, agg.qty, agg.cost, agg.orders, agg.weeks,
                               agg.returns, coalesce(rec.due, 0) AS due, coalesce(rec.on_time, 0) AS on_time
                          FROM agg JOIN customers c ON c.id = agg.customer_id LEFT JOIN rec ON rec.customer_id = agg.customer_id
                        """).param("o", actor.organizationId()).param("f", r.from()).param("t", r.to()).param("b", a.branch())
                .param("today", reports.today()).query(Raw.class).list();

        Map<String, Function<Raw, BigDecimal>> keys = Map.of(
                "net_revenue", Raw::net, "quantity", Raw::qty, "margin", x -> x.net().subtract(x.cost()),
                "orders", x -> BigDecimal.valueOf(x.orders()), "active_weeks", x -> BigDecimal.valueOf(x.weeks()),
                "on_time_percent", x -> x.due() == 0 ? BigDecimal.valueOf(-1) : pct(x.onTime(), x.due()),
                "returns", x -> BigDecimal.valueOf(x.returns()));
        Comparator<Raw> cmp = Comparator.comparing(keys.get(sort)).reversed().thenComparing(Raw::customerId);
        if (sort.equals("returns")) {
            cmp = Comparator.comparing(keys.get(sort)).thenComparing(Raw::customerId); // menos devoluções primeiro
        }
        List<Raw> sorted = raw.stream().sorted(cmp).limit(20).toList();
        boolean closing = a.closing();
        java.util.concurrent.atomic.AtomicInteger pos = new java.util.concurrent.atomic.AtomicInteger();
        List<CustomerRow> items = sorted.stream().map(x -> {
            BigDecimal margin = Decimals.money(x.net().subtract(x.cost()));
            return new CustomerRow(pos.incrementAndGet(), x.customerId(),
                    names ? x.name() : "Cliente " + x.customerId().toString().substring(0, 8),
                    Decimals.moneyStr(x.net()), unit == null ? null : x.qty().setScale(6, RoundingMode.HALF_UP).toPlainString(),
                    closing ? Decimals.moneyStr(margin) : null,
                    closing && x.net().signum() != 0 ? margin.multiply(BigDecimal.valueOf(100)).divide(x.net(), 2, RoundingMode.HALF_UP).toPlainString() : null,
                    x.orders(), x.weeks(), x.returns(), x.due(), x.onTime(),
                    x.due() == 0 ? null : pct(x.onTime(), x.due()).toPlainString());
        }).toList();
        List<Measure> measures = new java.util.ArrayList<>(List.of(
                new Measure("net_revenue", "Receita líquida", "Soma da receita líquida reconhecida nas retiradas/entregas do cliente menos devoluções."),
                new Measure("quantity", "Volume", "Quantidade retirada/entregue − devolvida" + (unit == null ? " (só com uma unidade-base no período)" : ", em " + unit) + "."),
                new Measure("orders", "Pedidos", "Pedidos distintos com retirada/entrega no período."),
                new Measure("active_weeks", "Recorrência", "Semanas (segunda a domingo) distintas em que o cliente comprou."),
                new Measure("on_time_percent", "Pontualidade", "Parcelas a prazo vencidas no período quitadas até o vencimento ÷ parcelas vencidas × 100."),
                new Measure("returns", "Devoluções", "Devoluções registradas no período (menos é melhor).")));
        if (closing) {
            measures.add(2, new Measure("margin", "Margem", "Receita líquida − custo histórico (CMV) das mesmas linhas."));
        }
        return new Ranking(r.from(), r.to(), r.label(), a.branch(), sort, unit, measures,
                closing ? List.of() : List.of("margin", "margin_percent"), items,
                "Sem nota única: cada coluna é uma medida própria; a ordem segue só a medida escolhida."
                        + (names ? "" : " Nomes ocultos: sem permissão de ver clientes."));
    }

    static BigDecimal pct(int a, int b) {
        return BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(b), 2, RoundingMode.HALF_UP);
    }
}
