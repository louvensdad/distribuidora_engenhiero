package br.com.engenheiro.api.central;

import br.com.engenheiro.api.accounting.PayrollService;
import br.com.engenheiro.api.accounting.TaxObligationService;
import br.com.engenheiro.api.finance.ExpenseService;
import br.com.engenheiro.api.finance.PeriodService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.inventory.AdjustmentService;
import br.com.engenheiro.api.inventory.LossService;
import br.com.engenheiro.api.logistics.VehicleExpenseService;
import br.com.engenheiro.api.orders.CashService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central de aprovações (F10): o que espera decisão DE QUEM ESTÁ PERGUNTANDO. Cada fonte só entra se o ator tem a
 * capacidade de decidir (aprovar/conferir) e de ver; a lista vem do próprio módulo, com o mesmo escopo e as mesmas
 * máscaras de campo. A decisão continua na tela do módulo (link), com a validação, a alçada e a trilha de lá — esta
 * central não aprova nada sozinha.
 */
@Service
public class ApprovalCenterService {
    public record Item(String kind, String sector, UUID id, String title, String detail, String amount,
                       String requested_by, OffsetDateTime requested_at, String path, String note) {
    }

    public record SectorCount(String sector, int pending) {
    }

    public record Center(List<Item> items, List<SectorCount> sectors) {
    }

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final TaxObligationService taxes;
    private final PayrollService payroll;
    private final LossService losses;
    private final AdjustmentService adjustments;
    private final ExpenseService expenses;
    private final VehicleExpenseService vehicleExpenses;
    private final CashService cash;
    private final PeriodService periods;

    public ApprovalCenterService(JdbcClient jdbc, AuthorizationService authz, TaxObligationService taxes,
                                 PayrollService payroll, LossService losses, AdjustmentService adjustments,
                                 ExpenseService expenses, VehicleExpenseService vehicleExpenses, CashService cash,
                                 PeriodService periods) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.taxes = taxes;
        this.payroll = payroll;
        this.losses = losses;
        this.adjustments = adjustments;
        this.expenses = expenses;
        this.vehicleExpenses = vehicleExpenses;
        this.cash = cash;
        this.periods = periods;
    }

    private boolean can(Actor actor, String resource, String action) {
        return authz.decide(actor, resource, action).allowed();
    }

    @Transactional(readOnly = true)
    public Center center(Actor actor) {
        List<Item> items = new ArrayList<>();
        if (can(actor, "taxes", "approve") && can(actor, "taxes", "view")) {
            for (var t : taxes.list(actor, "SUBMITTED")) {
                items.add(new Item("TAX", "ACCOUNTING", t.id(), "Guia " + t.kind() + ": " + t.description(),
                        "Competência " + t.competence() + " · vence " + t.due_date(), t.amount(), t.submitted_by_name(),
                        t.submitted_at(), "/finance/taxes?state=SUBMITTED", "Aprovar não paga: o pagamento é outra etapa."));
            }
        }
        if (can(actor, "payroll", "approve") && can(actor, "payroll", "view")) {
            for (var r : payroll.list(actor)) {
                if (r.state().equals("SUBMITTED")) {
                    items.add(new Item("PAYROLL", "ACCOUNTING", r.id(), "Folha: " + r.description(),
                            "Competência " + r.competence() + " · " + r.totals().people() + " pessoa(s)",
                            r.totals().company_cost(), r.submitted_by_name(), r.created_at(), "/finance/payroll", null));
                }
            }
        }
        if (can(actor, "inventory.losses", "approve") && can(actor, "inventory.losses", "view")) {
            for (var l : losses.list(actor, "PENDING", 100, null).items()) {
                items.add(new Item("LOSS", "COUNTER", l.id(), "Perda: " + l.product_name(),
                        l.quantity() + " " + l.unit() + " · " + l.reason() + " · " + l.branch(), l.total_cost(),
                        l.created_by_name(), l.created_at(), "/operation/stock", null));
            }
            for (var a : adjustments.list(actor, "PENDING", 100, null).items()) {
                items.add(new Item("ADJUSTMENT", "COUNTER", a.id(), "Ajuste de contagem: " + a.product_name(),
                        "Diferença " + a.difference() + " " + a.base_unit() + " · " + a.branch(), a.total_cost(),
                        a.created_by_name(), a.created_at(), "/operation/stock", null));
            }
        }
        if (can(actor, "expenses", "approve") && can(actor, "expenses", "view")) {
            Decision d = authz.decide(actor, "expenses", "approve");
            for (var x : expenses.list(actor, "PENDING", null, null, null, 100, null).items()) {
                boolean over = d.maxAmount().isPresent() && new BigDecimal(x.amount()).compareTo(d.maxAmount().get()) > 0;
                items.add(new Item("EXPENSE", "FINANCE", x.id(), "Despesa: " + x.description(),
                        x.category() + (x.subcategory() == null ? "" : " › " + x.subcategory()) + " · competência "
                                + x.competence(), x.amount(), x.created_by_name(), x.created_at(),
                        "/finance/expenses?state=PENDING", over ? "Acima da sua alçada: outra pessoa precisa aprovar." : null));
            }
        }
        boolean fuel = can(actor, "fuel", "approve");
        boolean vexp = can(actor, "expenses", "approve");
        if ((fuel || vexp) && (can(actor, "fuel", "view") || can(actor, "expenses", "view"))) {
            for (var e : vehicleExpenses.list(actor, null, null, "PENDING")) {
                if (e.kind().equals("FUEL") ? !fuel : !vexp) {
                    continue;
                }
                items.add(new Item("VEHICLE_EXPENSE", "DELIVERY", e.id(), "Gasto do veículo " + e.plate() + ": " + e.kind(),
                        (e.trip_number() == null ? "" : "Viagem V" + e.trip_number() + " · ") + e.occurred_on(),
                        e.amount(), e.created_by_name(), e.created_at(), "/operation/fleet/" + e.vehicle_id(), null));
            }
        }
        if (actor.admin()) { // conferência de caixa: função de administrador (ADR-017)
            for (var s : cash.list(actor)) {
                if (s.state().equals("CLOSED")) {
                    items.add(new Item("CASH_SESSION", "COUNTER", s.id(), "Conferir caixa de " + s.operator_name(),
                            "Esperado " + s.expected() + " · contado " + s.counted() + " · diferença " + s.difference(),
                            s.difference(), s.operator_name(), s.closed_at(), "/finance/my-cash", null));
                }
            }
        }
        if (can(actor, "payments", "confirm")) {
            items.addAll(pendingPayments(actor));
        }
        if (can(actor, "reports", "reopen_period") && can(actor, "reports", "view")) {
            for (var p : periods.list(actor)) {
                if (p.reopen_pending()) {
                    items.add(new Item("PERIOD_REOPEN", "FINANCE", null, "Reabrir o mês " + p.month(),
                            "Pedido de reabertura de período fechado", null, p.reopen_requested_by(), null,
                            "/finance/closing", null));
                }
            }
        }
        if (can(actor, "users", "edit_draft")) {
            items.addAll(jdbc.sql("""
                            SELECT 'ABSENCE' AS kind, 'TEAM' AS sector, a.id, 'Ausência: ' || u.display_name AS title,
                                   a.kind || ' de ' || a.start_date || ' a ' || a.end_date AS detail, NULL AS amount,
                                   cb.display_name AS requested_by, a.created_at AS requested_at,
                                   '/team/absences' AS path, NULL AS note
                              FROM employee_absences a JOIN memberships m ON m.id = a.membership_id
                              JOIN app_users u ON u.id = m.user_id JOIN app_users cb ON cb.id = a.created_by
                             WHERE a.organization_id = :o AND a.state = 'REQUESTED'
                             ORDER BY a.created_at LIMIT 100
                            """).param("o", actor.organizationId()).query(Item.class).list());
        }
        items.sort(Comparator.comparing(Item::requested_at, Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("COUNTER", "DELIVERY", "ACCOUNTING", "FINANCE", "TEAM")) {
            counts.put(s, 0);
        }
        items.forEach(i -> counts.merge(i.sector(), 1, Integer::sum));
        return new Center(items, counts.entrySet().stream().map(e -> new SectorCount(e.getKey(), e.getValue())).toList());
    }

    /** Pix/transferência aguardando conferência, no escopo da capacidade de confirmar. */
    private List<Item> pendingPayments(Actor actor) {
        Decision d = authz.decide(actor, "payments", "confirm");
        ScopeSql.Predicate p = ScopeSql.of(actor, d.scopes(),
                new ScopeSql.Columns("pm.organization_id", "pm.branch_id", null, "pm.created_by", null));
        var spec = jdbc.sql("""
                SELECT 'PAYMENT' AS kind, CASE o.type WHEN 'DELIVERY' THEN 'DELIVERY' ELSE 'COUNTER' END AS sector,
                       pm.id, 'Conferir pagamento do pedido #' || o.number AS title,
                       pm.method || ' · ' || c.name AS detail, CAST(pm.amount AS text) AS amount,
                       u.display_name AS requested_by, pm.created_at AS requested_at,
                       '/sales/orders/' || o.id AS path, NULL AS note
                  FROM payments pm JOIN orders o ON o.id = pm.order_id JOIN customers c ON c.id = o.customer_id
                  JOIN app_users u ON u.id = pm.created_by
                 WHERE pm.state = 'PENDING' AND """ + p.sql() + " ORDER BY pm.created_at LIMIT 100");
        for (Map.Entry<String, Object> e : p.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Item.class).list();
    }
}
