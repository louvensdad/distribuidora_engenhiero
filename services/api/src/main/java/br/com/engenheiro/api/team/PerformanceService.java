package br.com.engenheiro.api.team;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Desempenho por função (REP-08; cerebro/03): cada medida é separada, com fórmula pública e sentido (maior/menor é
 * melhor). Não existe nota única nem ranking geral; o destaque é por medida e só com dados no período. Ver: `reports.view`.
 */
@Service
public class PerformanceService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;

    public PerformanceService(JdbcClient jdbc, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    public record Value(UUID user_id, String person, String value) {
    }

    public record Measure(String code, String function, String label, String formula, String unit, boolean higher_is_better,
                          List<Value> values, String leader, String note) {
    }

    public record Report(LocalDate from, LocalDate to, List<Measure> measures, String rule) {
    }

    record Raw(UUID userId, String person, BigDecimal value) {
    }

    @Transactional(readOnly = true)
    public Report report(Actor actor, LocalDate from, LocalDate to) {
        authz.require(actor, "reports", "view");
        LocalDate f = from == null ? LocalDate.now(ZoneId.of("America/Sao_Paulo")).withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1) : to;
        if (!t.isAfter(f) || f.plusYears(1).isBefore(t)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Intervalo inválido (fim exclusivo, até 1 ano).");
        }
        UUID o = actor.organizationId();
        List<Measure> m = new ArrayList<>();
        m.add(measure("PICKUPS_FULFILLED", "Balcão (retirada)", "Retiradas registradas",
                "Quantidade de retiradas (cumprimentos de pedido de retirada) registradas pela pessoa no período.",
                "retiradas", true, false, query("""
                        SELECT f.actor_id AS user_id, u.display_name AS person, count(*) AS value
                          FROM fulfillments f JOIN orders o ON o.id = f.order_id JOIN app_users u ON u.id = f.actor_id
                         WHERE o.organization_id = :o AND o.type = 'PICKUP' AND f.kind = 'FULFILLMENT'
                           AND (f.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (f.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        m.add(measure("PICKUP_SALES", "Balcão (retirada)", "Vendas cumpridas no balcão",
                "Soma do valor líquido das retiradas registradas pela pessoa (receita reconhecida, sem pedido apenas reservado).",
                "R$", true, true, query("""
                        SELECT f.actor_id AS user_id, u.display_name AS person, sum(fl.net_revenue) AS value
                          FROM fulfillments f JOIN fulfillment_lines fl ON fl.fulfillment_id = f.id JOIN orders o ON o.id = f.order_id
                          JOIN app_users u ON u.id = f.actor_id
                         WHERE o.organization_id = :o AND o.type = 'PICKUP' AND f.kind = 'FULFILLMENT'
                           AND (f.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (f.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        m.add(measure("CASH_DIFFERENCE", "Caixa do turno", "Diferença de caixa (soma absoluta)",
                "Soma, sem sinal, das diferenças entre contado e esperado nos caixas fechados pela pessoa no período.",
                "R$", false, true, query("""
                        SELECT s.operator_id AS user_id, u.display_name AS person, sum(abs(s.difference)) AS value
                          FROM cash_sessions s JOIN app_users u ON u.id = s.operator_id
                         WHERE s.organization_id = :o AND s.state IN ('CLOSED', 'REVIEWED')
                           AND (s.closed_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (s.closed_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        m.add(measure("RECEIPTS", "Recebimento de fornecedor", "Recebimentos registrados",
                "Quantidade de recebimentos de mercadoria registrados (conferência de quantidade) pela pessoa no período.",
                "recebimentos", true, false, query("""
                        SELECT r.created_by AS user_id, u.display_name AS person, count(*) AS value
                          FROM inventory_receipts r JOIN app_users u ON u.id = r.created_by
                         WHERE r.organization_id = :o AND (r.created_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (r.created_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        m.add(measure("DELIVERIES", "Motorista", "Entregas concluídas",
                "Paradas entregues por completo pela pessoa no período (entrega parcial não conta aqui).",
                "entregas", true, false, query("""
                        SELECT s.delivered_by AS user_id, u.display_name AS person, count(*) AS value
                          FROM trip_stops s JOIN trips t ON t.id = s.trip_id JOIN app_users u ON u.id = s.delivered_by
                         WHERE t.organization_id = :o AND s.state = 'DELIVERED'
                           AND (s.delivered_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (s.delivered_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        m.add(measure("FAILED_STOPS", "Motorista", "Paradas não entregues ou parciais",
                "Paradas das viagens da pessoa que terminaram sem entrega completa (ausente, recusa, endereço, parcial). "
                        + "Mede ocorrência, não culpa: ver o motivo de cada uma.",
                "paradas", false, false, query("""
                        SELECT t.driver_id AS user_id, u.display_name AS person, count(*) AS value
                          FROM trip_stops s JOIN trips t ON t.id = s.trip_id JOIN app_users u ON u.id = t.driver_id
                         WHERE t.organization_id = :o AND s.state IN ('FAILED', 'PARTIAL') AND t.departed_at IS NOT NULL
                           AND (t.departed_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (t.departed_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        m.add(measure("TRIP_CASH_DIFFERENCE", "Motorista", "Diferença na prestação de contas (soma absoluta)",
                "Soma, sem sinal, da diferença entre o dinheiro entregue e o esperado nas viagens da pessoa retornadas no período.",
                "R$", false, true, query("""
                        SELECT t.driver_id AS user_id, u.display_name AS person, sum(abs(t.cash_difference)) AS value
                          FROM trips t JOIN app_users u ON u.id = t.driver_id
                         WHERE t.organization_id = :o AND t.state IN ('RETURNED', 'RECONCILED')
                           AND (t.returned_at AT TIME ZONE 'America/Sao_Paulo')::date >= :f
                           AND (t.returned_at AT TIME ZONE 'America/Sao_Paulo')::date < :t
                         GROUP BY 1, 2
                        """, o, f, t)));
        return new Report(f, t, m, "Sem nota única: cada medida é independente, com fórmula pública, e só compara quem "
                + "exerce a mesma função no período. Volume não é qualidade; leia as medidas juntas.");
    }

    private List<Raw> query(String sql, UUID o, LocalDate f, LocalDate t) {
        return jdbc.sql(sql).param("o", o).param("f", f).param("t", t).query(Raw.class).list();
    }

    private static Measure measure(String code, String function, String label, String formula, String unit,
                                   boolean higherBetter, boolean money, List<Raw> raw) {
        Comparator<Raw> cmp = Comparator.comparing(Raw::value);
        List<Raw> sorted = new ArrayList<>(raw);
        sorted.sort(higherBetter ? cmp.reversed() : cmp);
        List<Value> values = sorted.stream().map(r -> new Value(r.userId(), r.person(),
                money ? Decimals.moneyStr(r.value()) : Decimals.str(r.value()))).toList();
        String leader = null;
        String note = null;
        if (sorted.isEmpty()) {
            note = "Sem dados no período.";
        } else if (sorted.size() == 1) {
            note = "Só uma pessoa com dados: sem comparação.";
        } else if (sorted.get(0).value().compareTo(sorted.get(1).value()) == 0) {
            note = "Empate no primeiro lugar.";
        } else {
            leader = sorted.get(0).person();
        }
        return new Measure(code, function, label, formula, unit, higherBetter, values, leader, note);
    }
}
