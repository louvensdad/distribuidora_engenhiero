package br.com.engenheiro.api.reports;

import br.com.engenheiro.api.finance.FinanceReportService;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conciliação do período (F17, UAT-02): o relatório que o dono vê conferido contra os documentos operacionais que o
 * formam — o que foi entregue × a saída de estoque × a venda e o CMV lançados; o dinheiro recebido × os movimentos de
 * caixa; os caixas fechados × os seus movimentos; o estoque e as reservas × o histórico; e nenhum lançamento sem
 * documento de origem. Datas dos documentos no fuso comercial (São Paulo), a mesma base do livro.
 *
 * <p>Somente leitura. Exige `reports.view` no escopo da EMPRESA e os campos de fechamento (CMV/lucro); quem não os vê
 * recebe 403 — a conciliação nunca é uma porta lateral para o custo.
 */
@Service
public class PilotReconciliationService {

    static final String TZ = "America/Sao_Paulo";
    private static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.05");

    /** Tipo de origem do lançamento → consulta que confirma que o documento existe (parâmetro :id). */
    static final Map<String, String> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("order_fulfillment", "SELECT count(*) FROM fulfillments WHERE id = :id AND kind = 'FULFILLMENT'");
        SOURCES.put("order_return", "SELECT count(*) FROM fulfillments WHERE id = :id AND kind = 'RETURN'");
        SOURCES.put("payment", "SELECT count(*) FROM payments WHERE id = :id");
        SOURCES.put("inventory_loss", "SELECT count(*) FROM inventory_losses WHERE id = :id");
        SOURCES.put("inventory_adjustment", "SELECT count(*) FROM inventory_adjustments WHERE id = :id");
        SOURCES.put("inventory_receipt", "SELECT count(*) FROM inventory_receipts WHERE id = :id");
        SOURCES.put("supplier_return", "SELECT count(*) FROM stock_movements WHERE source_type = 'supplier_return' AND source_id = :id");
        SOURCES.put("expense", "SELECT count(*) FROM expenses WHERE id = :id");
        SOURCES.put("vehicle_expense", "SELECT count(*) FROM vehicle_expenses WHERE id = :id");
        SOURCES.put("tax_obligation", "SELECT count(*) FROM tax_obligations WHERE id = :id");
        SOURCES.put("payroll_run", "SELECT count(*) FROM payroll_runs WHERE id = :id");
        SOURCES.put("payable_payment", "SELECT count(*) FROM payable_settlements WHERE id = :id");
        SOURCES.put("receivable_settlement", "SELECT count(*) FROM receivable_settlements WHERE id = :id");
        SOURCES.put("fin_transfer", "SELECT count(*) FROM fin_transfers WHERE id = :id");
        SOURCES.put("fin_account", "SELECT count(*) FROM fin_accounts WHERE id = :id");
    }

    public record Check(String code, String area, String label, String unit, String expected, String actual,
                        String difference, String status, String explanation) {
    }

    public record Summary(String net_revenue, String cogs, String expenses_total, String losses, String profit,
                          int fulfillments, int payments, int cash_sessions_closed) {
    }

    public record PilotReconciliation(LocalDate from, LocalDate to, String label, String timezone, String status,
                                      Summary report, List<Check> checks, String note, OffsetDateTime checked_at) {
    }

    private final JdbcClient jdbc;
    private final ReportService reports;

    public PilotReconciliationService(JdbcClient jdbc, ReportService reports) {
        this.jdbc = jdbc;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public PilotReconciliation reconcile(Actor actor, ReportService.Filter filter) {
        ReportService.Access a = reports.access(actor, "reports", "view", null);
        if (!a.organization() || !a.closing()) {
            throw ApiException.forbidden();
        }
        ReportService.Range r = reports.range(filter);
        return reconcile(actor.organizationId(), r);
    }

    PilotReconciliation reconcile(UUID org, ReportService.Range r) {
        LocalDate f = r.from();
        LocalDate t = r.to();
        FinanceReportService.Result rep = ReportService.result(reports.daily(org, f, t, null).total(f, t));
        List<Check> checks = new ArrayList<>();

        // ---- vendas: entregas (documentos) × relatório ----
        record Sale(BigDecimal net, BigDecimal cost, BigDecimal qty, int docs) {
        }
        Sale sale = jdbc.sql("""
                        SELECT coalesce(sum(x.net), 0) AS net, coalesce(sum(x.cost), 0) AS cost,
                               coalesce(sum(x.qty), 0) AS qty, CAST(count(*) FILTER (WHERE x.kind = 'FULFILLMENT') AS int) AS docs
                          FROM (SELECT f.kind, sum(fl.net_revenue) AS net, round(sum(fl.cost), 2) AS cost,
                                       sum(fl.base_quantity) AS qty
                                  FROM fulfillments f JOIN fulfillment_lines fl ON fl.fulfillment_id = f.id
                                  JOIN orders o ON o.id = f.order_id
                                 WHERE o.organization_id = :o
                                   AND (f.occurred_at AT TIME ZONE '%s')::date >= :f AND (f.occurred_at AT TIME ZONE '%s')::date < :t
                                 GROUP BY f.id, f.kind) x
                        """.formatted(TZ, TZ)).param("o", org).param("f", f).param("t", t).query(Sale.class).single();
        checks.add(money("SALES_NET", "Vendas", "Vendas líquidas: entregas e devoluções do período × relatório",
                sale.net(), rep.netRevenue(),
                "Soma das linhas entregues (menos devoluções) dos pedidos. Pedido só cadastrado ou só pago não é venda."));
        checks.add(money("COGS", "Vendas", "CMV: custo das entregas do período × relatório", sale.cost(), rep.cogs(),
                "Custo histórico de cada saída (custo médio no momento da entrega), sem perdas."));

        // ---- estoque: entregas × saídas físicas ----
        record Stock(BigDecimal qty, BigDecimal cost) {
        }
        Stock out = jdbc.sql("""
                        SELECT coalesce(-sum(quantity), 0) AS qty, coalesce(-sum(total_cost), 0) AS cost FROM stock_movements
                         WHERE organization_id = :o AND kind IN ('SALE_ISSUE', 'SALE_RETURN')
                           AND (occurred_at AT TIME ZONE '%s')::date >= :f AND (occurred_at AT TIME ZONE '%s')::date < :t
                        """.formatted(TZ, TZ)).param("o", org).param("f", f).param("t", t).query(Stock.class).single();
        checks.add(quantity("SALES_STOCK_QTY", "Estoque", "Quantidade entregue × saída de estoque por venda (unidade base)",
                sale.qty(), out.qty(), "Cada entrega baixa o estoque uma única vez; devolução volta ao estoque."));
        checks.add(rounding(money("SALES_STOCK_COST", "Estoque", "Custo das entregas × custo das saídas de estoque",
                sale.cost(), Decimals.money(out.cost()),
                "Mesma saída vista pelo pedido e pelo estoque; centavos podem vir do arredondamento do custo (6 casas).")));

        BigDecimal lossCost = jdbc.sql("""
                        SELECT coalesce(sum(round(-total_cost, 2)), 0) FROM stock_movements
                         WHERE organization_id = :o AND kind IN ('LOSS', 'LOSS_REVERSAL', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT')
                           AND (occurred_at AT TIME ZONE '%s')::date >= :f AND (occurred_at AT TIME ZONE '%s')::date < :t
                        """.formatted(TZ, TZ)).param("o", org).param("f", f).param("t", t).query(BigDecimal.class).single();
        checks.add(money("LOSSES", "Estoque", "Perdas e ajustes aprovados no estoque × perdas do relatório", lossCost,
                rep.losses(), "Perda é custo do estoque, separada do CMV (a venda seguinte não a inclui)."));

        // ---- dinheiro: recebimentos × caixa × livro ----
        record Cash(BigDecimal confirmed, BigDecimal reversed, int docs) {
        }
        Cash pay = jdbc.sql("""
                        SELECT coalesce(sum(amount) FILTER (WHERE confirmed_at IS NOT NULL
                                   AND (confirmed_at AT TIME ZONE '%1$s')::date >= :f AND (confirmed_at AT TIME ZONE '%1$s')::date < :t), 0) AS confirmed,
                               coalesce(sum(amount) FILTER (WHERE reversed_at IS NOT NULL
                                   AND (reversed_at AT TIME ZONE '%1$s')::date >= :f AND (reversed_at AT TIME ZONE '%1$s')::date < :t), 0) AS reversed,
                               CAST(count(*) FILTER (WHERE confirmed_at IS NOT NULL
                                   AND (confirmed_at AT TIME ZONE '%1$s')::date >= :f AND (confirmed_at AT TIME ZONE '%1$s')::date < :t) AS int) AS docs
                          FROM payments WHERE organization_id = :o AND method = :m
                        """.formatted(TZ)).param("o", org).param("f", f).param("t", t).param("m", "CASH")
                .query(Cash.class).single();
        BigDecimal drawer = jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN m.direction = 'IN' THEN m.amount ELSE -m.amount END), 0)
                          FROM cash_movements m JOIN cash_sessions s ON s.id = m.cash_session_id
                         WHERE s.organization_id = :o AND m.source_type IN ('payment', 'payment_reversal')
                           AND (m.occurred_at AT TIME ZONE '%s')::date >= :f AND (m.occurred_at AT TIME ZONE '%s')::date < :t
                        """.formatted(TZ, TZ)).param("o", org).param("f", f).param("t", t).query(BigDecimal.class).single();
        checks.add(money("CASH_PAYMENTS", "Caixa", "Pagamentos em dinheiro confirmados (− estornos) × entradas nos caixas",
                pay.confirmed().subtract(pay.reversed()), drawer,
                "Entra no caixa só o valor do pedido; o troco não é receita nem despesa."));

        Cash all = jdbc.sql("""
                        SELECT coalesce(sum(amount) FILTER (WHERE confirmed_at IS NOT NULL
                                   AND (confirmed_at AT TIME ZONE '%1$s')::date >= :f AND (confirmed_at AT TIME ZONE '%1$s')::date < :t), 0) AS confirmed,
                               coalesce(sum(amount) FILTER (WHERE reversed_at IS NOT NULL AND confirmed_at IS NOT NULL
                                   AND (reversed_at AT TIME ZONE '%1$s')::date >= :f AND (reversed_at AT TIME ZONE '%1$s')::date < :t), 0) AS reversed,
                               CAST(count(*) FILTER (WHERE confirmed_at IS NOT NULL
                                   AND (confirmed_at AT TIME ZONE '%1$s')::date >= :f AND (confirmed_at AT TIME ZONE '%1$s')::date < :t) AS int) AS docs
                          FROM payments WHERE organization_id = :o AND method <> 'ON_ACCOUNT'
                        """.formatted(TZ)).param("o", org).param("f", f).param("t", t).query(Cash.class).single();
        BigDecimal ledgerReceipts = jdbc.sql("""
                        SELECT coalesce(sum(l.credit - l.debit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.organization_id = :o AND e.source_type = 'payment' AND l.account = 'CLIENTES'
                           AND e.entry_date >= :f AND e.entry_date < :t
                        """).param("o", org).param("f", f).param("t", t).query(BigDecimal.class).single();
        checks.add(money("RECEIPTS_LEDGER", "Caixa", "Recebimentos confirmados de todos os meios (− estornos) × livro",
                all.confirmed().subtract(all.reversed()), ledgerReceipts,
                "Comprovante anexado ou Pix pendente não conta: só pagamento confirmado. A prazo não é dinheiro."));

        record Session(BigDecimal expected, BigDecimal recomputed, int sessions) {
        }
        Session ses = jdbc.sql("""
                        SELECT coalesce(sum(s.expected), 0) AS expected,
                               coalesce(sum(s.opening_float + coalesce((SELECT sum(CASE WHEN m.direction = 'IN' THEN m.amount ELSE -m.amount END)
                                                                          FROM cash_movements m WHERE m.cash_session_id = s.id), 0)), 0) AS recomputed,
                               CAST(count(*) AS int) AS sessions
                          FROM cash_sessions s
                         WHERE s.organization_id = :o AND s.state IN ('CLOSED', 'REVIEWED')
                           AND (s.closed_at AT TIME ZONE '%s')::date >= :f AND (s.closed_at AT TIME ZONE '%s')::date < :t
                        """.formatted(TZ, TZ)).param("o", org).param("f", f).param("t", t).query(Session.class).single();
        checks.add(money("CASH_SESSIONS", "Caixa", "Caixas fechados no período: esperado gravado × fundo + movimentos",
                ses.recomputed(), ses.expected(),
                ses.sessions() + " caixa(s) fechado(s). A diferença contada × esperada de cada turno fica na conferência do caixa."));

        // ---- estado atual: estoque e reservas × histórico; pedidos ----
        BigDecimal physicalGap = jdbc.sql("""
                        SELECT coalesce(sum(abs(b.physical - coalesce(m.q, 0))), 0)
                          FROM inventory_balances b JOIN branches br ON br.id = b.branch_id
                          LEFT JOIN (SELECT branch_id, product_id, sum(quantity) AS q FROM stock_movements
                                      WHERE organization_id = :o GROUP BY branch_id, product_id) m
                                 ON m.branch_id = b.branch_id AND m.product_id = b.product_id
                         WHERE br.organization_id = :o
                        """).param("o", org).query(BigDecimal.class).single();
        checks.add(quantity("STOCK_PHYSICAL", "Estoque", "Estoque físico atual × soma de todas as movimentações",
                BigDecimal.ZERO, physicalGap, "Retrato de agora (não só do período): todo saldo nasce de movimentação registrada."));
        BigDecimal reservedGap = jdbc.sql("""
                        SELECT coalesce(sum(abs(b.reserved - coalesce(r.q, 0))), 0)
                          FROM inventory_balances b JOIN branches br ON br.id = b.branch_id
                          LEFT JOIN (SELECT branch_id, product_id, sum(quantity) AS q FROM stock_reservations
                                      WHERE organization_id = :o AND state = 'ACTIVE' GROUP BY branch_id, product_id) r
                                 ON r.branch_id = b.branch_id AND r.product_id = b.product_id
                         WHERE br.organization_id = :o
                        """).param("o", org).query(BigDecimal.class).single();
        checks.add(quantity("STOCK_RESERVED", "Estoque", "Reservado atual × reservas ativas dos pedidos",
                BigDecimal.ZERO, reservedGap, "Pedido cadastrado reserva; entrega consome; cancelamento libera."));
        BigDecimal recognizedGap = jdbc.sql("""
                        SELECT coalesce(sum(abs(ol.recognized_net - coalesce(x.net, 0))), 0)
                          FROM order_lines ol JOIN orders o ON o.id = ol.order_id
                          LEFT JOIN (SELECT fl.order_line_id, sum(fl.net_revenue) AS net FROM fulfillment_lines fl
                                       JOIN fulfillments f ON f.id = fl.fulfillment_id WHERE f.kind = 'FULFILLMENT'
                                      GROUP BY fl.order_line_id) x ON x.order_line_id = ol.id
                         WHERE o.organization_id = :o
                        """).param("o", org).query(BigDecimal.class).single();
        checks.add(money("ORDERS_RECOGNIZED", "Vendas", "Venda reconhecida em cada linha de pedido × suas entregas",
                BigDecimal.ZERO, recognizedGap, "Entrega parcial reconhece só o entregue; repetir a entrega não duplica."));

        // ---- livro: nenhum lançamento do período sem documento de origem ----
        record Src(String sourceType, UUID sourceId) {
        }
        List<Src> sources = jdbc.sql("""
                        SELECT DISTINCT source_type, source_id FROM journal_entries
                         WHERE organization_id = :o AND coalesce(competence, entry_date) >= :f AND coalesce(competence, entry_date) < :t
                        """).param("o", org).param("f", f).param("t", t).query(Src.class).list();
        int orphans = 0;
        Set<String> unknown = new TreeSet<>();
        for (Src s : sources) {
            String q = SOURCES.get(s.sourceType());
            if (q == null) {
                unknown.add(s.sourceType());
            } else if (jdbc.sql(q).param("id", s.sourceId()).query(Integer.class).single() == 0) {
                orphans++;
            }
        }
        if (!unknown.isEmpty()) {
            orphans += unknown.size();
        }
        checks.add(new Check("LEDGER_SOURCES", "Livro", "Lançamentos do período com documento de origem existente", "documentos",
                "0", Integer.toString(orphans), Integer.toString(orphans), orphans == 0 ? "OK" : "DIVERGENT",
                sources.size() + " documento(s) de origem conferido(s)"
                        + (unknown.isEmpty() ? "." : "; tipo(s) de origem sem verificação: " + String.join(", ", unknown) + ".")));

        String status = checks.stream().allMatch(c -> Set.of("OK", "ROUNDING").contains(c.status())) ? "OK" : "DIVERGENT";
        Summary sum = new Summary(Decimals.moneyStr(rep.netRevenue()), Decimals.moneyStr(rep.cogs()),
                Decimals.moneyStr(rep.expensesTotal()), Decimals.moneyStr(rep.losses()), Decimals.moneyStr(rep.profit()),
                sale.docs(), all.docs(), ses.sessions());
        return new PilotReconciliation(f, t, r.label(), TZ, status, sum, checks,
                "Conferência de leitura: não altera nada. Integração fiscal e bancária não configurada não entra como concluída.",
                OffsetDateTime.now());
    }

    static Check money(String code, String area, String label, BigDecimal expected, BigDecimal actual, String explanation) {
        BigDecimal diff = actual.subtract(expected);
        return new Check(code, area, label, "BRL", Decimals.moneyStr(expected), Decimals.moneyStr(actual),
                Decimals.moneyStr(diff), diff.signum() == 0 ? "OK" : "DIVERGENT", explanation);
    }

    static Check quantity(String code, String area, String label, BigDecimal expected, BigDecimal actual, String explanation) {
        BigDecimal diff = actual.subtract(expected);
        return new Check(code, area, label, "base", Decimals.str(expected), Decimals.str(actual), Decimals.str(diff),
                diff.signum() == 0 ? "OK" : "DIVERGENT", explanation);
    }

    static Check rounding(Check c) {
        BigDecimal d = new BigDecimal(c.difference()).abs();
        if (d.signum() != 0 && d.compareTo(ROUNDING_TOLERANCE) <= 0) {
            return new Check(c.code(), c.area(), c.label(), c.unit(), c.expected(), c.actual(), c.difference(), "ROUNDING",
                    c.explanation());
        }
        return c;
    }
}
