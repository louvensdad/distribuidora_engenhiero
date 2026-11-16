package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Períodos mensais (FIN-07): fechar grava o retrato do resultado e da conciliação e trava o livro do mês (serviço +
 * gatilho). Reabrir exige solicitação com motivo e aprovação por quem tem `reports.reopen_period` — outra pessoa; o
 * administrador pode reabrir direto, registrando o motivo como solicitação e aprovação. Tudo com histórico e auditoria.
 */
@Service
public class PeriodService {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final FinanceReportService reports;
    private final JsonMapper json;

    public PeriodService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, FinanceReportService reports,
                         JsonMapper json) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.reports = reports;
        this.json = json;
    }

    public record ReasonCommand(String reason) {
    }

    public record EventView(String action, String actor_name, String reason, String state, OffsetDateTime occurred_at) {
    }

    public record PeriodView(String id, LocalDate month, String state, OffsetDateTime closed_at, String closed_by_name,
                             boolean reopen_pending, String reopen_requested_by, String profit_at_close,
                             String reconciliation_at_close, List<EventView> events, long version) {
    }

    record Row(UUID id, LocalDate month, String state, OffsetDateTime closedAt, String closedByName, String snapshot,
               long version) {
    }

    @Transactional(readOnly = true)
    public List<PeriodView> list(Actor actor) {
        authz.require(actor, "reports", "view");
        LocalDate current = LocalDate.now(Journal.COMMERCIAL).withDayOfMonth(1);
        Map<LocalDate, Row> rows = new LinkedHashMap<>();
        for (Row r : jdbc.sql(SELECT + " WHERE p.organization_id = :o ORDER BY p.month DESC").param("o", actor.organizationId())
                .query(Row.class).list()) {
            rows.put(r.month(), r);
        }
        List<PeriodView> out = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            LocalDate m = current.minusMonths(i);
            Row r = rows.remove(m);
            out.add(view(m, r));
        }
        rows.values().forEach(r -> out.add(view(r.month(), r)));
        return out;
    }

    @Transactional
    public PeriodView close(Actor actor, String id, ReasonCommand cmd) {
        authz.require(actor, "reports", "close_period");
        LocalDate m = month(id);
        if (!m.isBefore(LocalDate.now(Journal.COMMERCIAL).withDayOfMonth(1))) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só mês encerrado pode ser fechado.");
        }
        Row r = lockOrCreate(actor.organizationId(), m);
        if (r.state().equals("CLOSED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Período já fechado.");
        }
        FinanceReportService.Reconciliation rec = reports.reconcile(actor.organizationId());
        if (rec.status().equals("DIVERGENT")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Conciliação com divergência: resolva antes de fechar ("
                    + String.join(", ", rec.checks().stream().filter(c -> c.status().equals("DIVERGENT")).map(c -> c.label()).toList())
                    + ").");
        }
        FinanceReportService.Summary s = reports.summaryUnchecked(actor.organizationId(), m, m.plusMonths(1));
        String snapshot = json.writeValueAsString(Map.of("summary", s, "reconciliation", rec));
        jdbc.sql("""
                        UPDATE fin_periods SET state = 'CLOSED', closed_at = now(), closed_by = :by, snapshot = CAST(:s AS jsonb),
                               version = version + 1 WHERE id = :id
                        """).param("by", actor.userId()).param("s", snapshot).param("id", r.id()).update();
        event(r.id(), "CLOSE", actor.userId(), Text.trim(cmd == null ? null : cmd.reason()), null);
        audit.record(PayableService.entry(actor, "periods.close", "fin_period", r.id(), Map.of("month", id,
                "profit", s.profit())));
        return view(m, find(actor.organizationId(), m).orElseThrow());
    }

    @Transactional
    public PeriodView requestReopen(Actor actor, String id, ReasonCommand cmd) {
        authz.require(actor, "reports", "view");
        LocalDate m = month(id);
        String reason = FinAccountService.requireReason(cmd == null ? null : cmd.reason());
        Row r = lockOrCreate(actor.organizationId(), m);
        if (!r.state().equals("CLOSED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Período não está fechado.");
        }
        if (pending(r.id()).isPresent()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Já existe solicitação de reabertura pendente.");
        }
        event(r.id(), "REOPEN_REQUEST", actor.userId(), reason, "PENDING");
        audit.record(PayableService.entry(actor, "periods.reopen_request", "fin_period", r.id(), Map.of("month", id,
                "reason", reason)));
        return view(m, find(actor.organizationId(), m).orElseThrow());
    }

    @Transactional
    public PeriodView approveReopen(Actor actor, String id, ReasonCommand cmd) {
        authz.require(actor, "reports", "reopen_period");
        LocalDate m = month(id);
        Row r = lockOrCreate(actor.organizationId(), m);
        if (!r.state().equals("CLOSED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Período não está fechado.");
        }
        String note = Text.trim(cmd == null ? null : cmd.reason());
        Optional<Pending> p = pending(r.id());
        if (p.isPresent()) {
            if (p.get().actorId().equals(actor.userId()) && !actor.admin()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "A reabertura deve ser aprovada por outra pessoa.");
            }
            jdbc.sql("UPDATE fin_period_events SET state = 'DONE' WHERE id = :id").param("id", p.get().id()).update();
        } else if (actor.admin()) {
            note = FinAccountService.requireReason(note); // dono reabre direto, com motivo registrado
            event(r.id(), "REOPEN_REQUEST", actor.userId(), note, "DONE");
        } else {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Não há solicitação de reabertura pendente.");
        }
        jdbc.sql("UPDATE fin_periods SET state = 'OPEN', version = version + 1 WHERE id = :id").param("id", r.id()).update();
        event(r.id(), "REOPEN_APPROVE", actor.userId(), note, null);
        audit.record(PayableService.entry(actor, "periods.reopen", "fin_period", r.id(), Map.of("month", id)));
        return view(m, find(actor.organizationId(), m).orElseThrow());
    }

    @Transactional
    public PeriodView rejectReopen(Actor actor, String id, ReasonCommand cmd) {
        authz.require(actor, "reports", "reopen_period");
        LocalDate m = month(id);
        String reason = FinAccountService.requireReason(cmd == null ? null : cmd.reason());
        Row r = lockOrCreate(actor.organizationId(), m);
        Pending p = pending(r.id()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_TRANSITION,
                "Não há solicitação de reabertura pendente."));
        jdbc.sql("UPDATE fin_period_events SET state = 'DONE' WHERE id = :id").param("id", p.id()).update();
        event(r.id(), "REOPEN_REJECT", actor.userId(), reason, null);
        audit.record(PayableService.entry(actor, "periods.reopen_reject", "fin_period", r.id(), Map.of("month", id)));
        return view(m, find(actor.organizationId(), m).orElseThrow());
    }

    // ---------------- apoio ----------------
    record Pending(UUID id, UUID actorId, String actorName) {
    }

    private Optional<Pending> pending(UUID periodId) {
        return jdbc.sql("""
                        SELECT e.id, e.actor_id, u.display_name AS actor_name FROM fin_period_events e
                          JOIN app_users u ON u.id = e.actor_id
                         WHERE e.period_id = :p AND e.action = 'REOPEN_REQUEST' AND e.state = 'PENDING'
                        """).param("p", periodId).query(Pending.class).optional();
    }

    private void event(UUID period, String action, UUID actor, String reason, String state) {
        jdbc.sql("""
                        INSERT INTO fin_period_events (id, period_id, action, actor_id, reason, state)
                        VALUES (:id, :p, :a, :u, :r, :s)
                        """).param("id", Ids.newId()).param("p", period).param("a", action).param("u", actor)
                .param("r", reason).param("s", state).update();
    }

    private Row lockOrCreate(UUID org, LocalDate m) {
        jdbc.sql("""
                        INSERT INTO fin_periods (id, organization_id, month, state) VALUES (:id, :o, :m, 'OPEN')
                        ON CONFLICT (organization_id, month) DO NOTHING
                        """).param("id", Ids.newId()).param("o", org).param("m", m).update();
        jdbc.sql("SELECT id FROM fin_periods WHERE organization_id = :o AND month = :m FOR UPDATE").param("o", org)
                .param("m", m).query(UUID.class).single();
        return find(org, m).orElseThrow();
    }

    private Optional<Row> find(UUID org, LocalDate m) {
        return jdbc.sql(SELECT + " WHERE p.organization_id = :o AND p.month = :m").param("o", org).param("m", m)
                .query(Row.class).optional();
    }

    static final String SELECT = """
            SELECT p.id, p.month, p.state, p.closed_at, u.display_name AS closed_by_name, CAST(p.snapshot AS text) AS snapshot,
                   p.version
              FROM fin_periods p LEFT JOIN app_users u ON u.id = p.closed_by
            """;

    private PeriodView view(LocalDate m, Row r) {
        String id = YearMonth.from(m).toString();
        if (r == null) {
            return new PeriodView(id, m, "OPEN", null, null, false, null, null, null, List.of(), 0);
        }
        List<EventView> events = jdbc.sql("""
                        SELECT e.action, u.display_name AS actor_name, e.reason, e.state, e.occurred_at FROM fin_period_events e
                          JOIN app_users u ON u.id = e.actor_id WHERE e.period_id = :p ORDER BY e.occurred_at
                        """).param("p", r.id()).query(EventView.class).list();
        Optional<Pending> p = pending(r.id());
        String profit = null;
        String recon = null;
        if (r.snapshot() != null) {
            var node = json.readTree(r.snapshot());
            profit = node.path("summary").path("profit").asString();
            recon = node.path("reconciliation").path("status").asString();
        }
        return new PeriodView(id, m, r.state(), r.closedAt(), r.closedByName(), p.isPresent(),
                p.map(Pending::actorName).orElse(null), profit, recon, events, r.version());
    }

    static LocalDate month(String id) {
        try {
            return YearMonth.parse(id).atDay(1);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Período inválido (use AAAA-MM).",
                    List.of(new FieldError("id", "Formato AAAA-MM")));
        }
    }
}
