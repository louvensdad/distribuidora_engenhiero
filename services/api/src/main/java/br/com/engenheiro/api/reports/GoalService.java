package br.com.engenheiro.api.reports;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metas semanais/mensais (REP-07). O realizado nunca é digitado: vem de {@link ReportService#totals} para o mesmo período
 * e unidade. Faltante, percentual e ritmo esperado têm fórmula explícita na resposta. `goals.create` cria, ativa e
 * cancela; `goals.edit_draft` edita rascunho; `goals.view` lê (unidade conforme o escopo). Meta de lucro só mostra o
 * realizado a quem vê o fechamento (custo de compra e margem).
 */
@Service
public class GoalService {
    static final Set<String> METRICS = Set.of("NET_REVENUE", "SOLD_QUANTITY", "PROFIT");

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final AuditService audit;

    public GoalService(JdbcClient jdbc, ReportService reports, AuditService audit) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.audit = audit;
    }

    public record Command(String metric, String unit, String period_kind, LocalDate period_start, String target, String title,
                          String notes, UUID branch_id) {
    }

    public record ReasonCommand(String reason) {
    }

    public record Progress(String realized, String remaining, String percent, String expected_by_now, int days_elapsed,
                           int days_total, String status, String formula) {
    }

    public record GoalView(UUID id, String metric, String metric_label, String unit, String period_kind,
                           LocalDate period_start, LocalDate period_end, String period_label, String target, String title,
                           String notes, UUID branch_id, String branch_name, String state, String cancel_reason,
                           String created_by_name, OffsetDateTime created_at, String activated_by_name,
                           OffsetDateTime activated_at, String cancelled_by_name, OffsetDateTime cancelled_at,
                           Progress progress, long version) {
    }

    record Row(UUID id, UUID organizationId, String metric, String unit, String periodKind, LocalDate periodStart,
               BigDecimal target, String title, String notes, UUID branchId, String branchName, String state,
               String cancelReason, String createdByName, OffsetDateTime createdAt, String activatedByName,
               OffsetDateTime activatedAt, String cancelledByName, OffsetDateTime cancelledAt, long version) {
    }

    private static final String SELECT = """
            SELECT g.id, g.organization_id, g.metric, g.unit, g.period_kind, g.period_start, g.target, g.title, g.notes,
                   g.branch_id, b.name AS branch_name, g.state, g.cancel_reason, cu.display_name AS created_by_name, g.created_at,
                   au.display_name AS activated_by_name, g.activated_at, xu.display_name AS cancelled_by_name, g.cancelled_at,
                   g.version
              FROM goals g JOIN app_users cu ON cu.id = g.created_by LEFT JOIN branches b ON b.id = g.branch_id
              LEFT JOIN app_users au ON au.id = g.activated_by LEFT JOIN app_users xu ON xu.id = g.cancelled_by
            """;

    // ---------------- leitura ----------------
    /** Metas cujo período cruza [from, to) — padrão: mês corrente e semanas que o tocam. */
    @Transactional(readOnly = true)
    public List<GoalView> list(Actor actor, LocalDate from, LocalDate to, UUID branchId) {
        ReportService.Access a = reports.access(actor, "goals", "view", branchId);
        LocalDate f = from == null ? reports.today().withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1) : to;
        if (!t.isAfter(f) || f.plusYears(3).isBefore(t)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Intervalo inválido (até 3 anos).");
        }
        boolean closing = a.closing();
        List<GoalView> out = new ArrayList<>();
        for (Row r : jdbc.sql(SELECT + """
                         WHERE g.organization_id = :o AND g.period_start < :t
                           AND (CASE g.period_kind WHEN 'WEEK' THEN g.period_start + 7
                                ELSE CAST(g.period_start + interval '1 month' AS date) END) > :f
                           AND (CAST(:b AS uuid) IS NULL OR g.branch_id IS NOT DISTINCT FROM CAST(:b AS uuid))
                         ORDER BY g.period_start DESC, g.created_at DESC LIMIT 500
                        """).param("o", actor.organizationId()).param("f", f).param("t", t).param("b", a.branch())
                .query(Row.class).list()) {
            out.add(view(actor, r, closing));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public GoalView get(Actor actor, UUID id) {
        Row r = visible(actor, id, "view");
        return view(actor, r, reports.access(actor, "goals", "view", r.branchId()).closing());
    }

    // ---------------- comandos ----------------
    @Transactional
    public GoalView create(Actor actor, Command cmd) {
        reports.access(actor, "goals", "create", cmd == null ? null : cmd.branch_id());
        Valid v = validate(cmd);
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO goals (id, organization_id, branch_id, metric, unit, period_kind, period_start, target, title,
                                           notes, created_by)
                        VALUES (:id, :o, :b, :m, :u, :k, :s, :t, :ti, :n, :by)
                        """).param("id", id).param("o", actor.organizationId()).param("b", cmd.branch_id())
                .param("m", v.metric()).param("u", v.unit()).param("k", v.kind()).param("s", v.start()).param("t", v.target())
                .param("ti", v.title()).param("n", v.notes()).param("by", actor.userId()).update();
        audit.record(entry(actor, "goals.create", id, Map.of("metric", v.metric(), "period", v.kind() + " " + v.start(),
                "target", v.target().toPlainString())));
        return get(actor, id);
    }

    @Transactional
    public GoalView update(Actor actor, UUID id, long version, Command cmd) {
        Row r = locked(actor, id, "edit_draft");
        requireVersion(r, version);
        if (!r.state().equals("DRAFT")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só rascunho é editável; para mudar uma meta ativa, cancele com motivo e crie outra.");
        }
        if (cmd != null && cmd.branch_id() != null && !cmd.branch_id().equals(r.branchId())) {
            reports.access(actor, "goals", "edit_draft", cmd.branch_id());
        }
        Valid v = validate(cmd);
        jdbc.sql("""
                        UPDATE goals SET branch_id = :b, metric = :m, unit = :u, period_kind = :k, period_start = :s, target = :t,
                               title = :ti, notes = :n, version = version + 1 WHERE id = :id
                        """).param("b", cmd.branch_id()).param("m", v.metric()).param("u", v.unit()).param("k", v.kind())
                .param("s", v.start()).param("t", v.target()).param("ti", v.title()).param("n", v.notes()).param("id", id).update();
        audit.record(entry(actor, "goals.edit_draft", id, Map.of("target", v.target().toPlainString())));
        return get(actor, id);
    }

    @Transactional
    public GoalView activate(Actor actor, UUID id, long version) {
        Row r = locked(actor, id, "create");
        requireVersion(r, version);
        if (!r.state().equals("DRAFT")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só rascunho pode ser ativado.");
        }
        try {
            jdbc.sql("UPDATE goals SET state = 'ACTIVE', activated_by = :by, activated_at = now(), version = version + 1 WHERE id = :id")
                    .param("by", actor.userId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Já existe meta ativa para este indicador, período e unidade.");
        }
        audit.record(entry(actor, "goals.activate", id, Map.of("target", r.target().toPlainString())));
        return get(actor, id);
    }

    @Transactional
    public GoalView cancel(Actor actor, UUID id, long version, ReasonCommand cmd) {
        Row r = locked(actor, id, "create");
        requireVersion(r, version);
        String reason = Text.trim(cmd == null ? null : cmd.reason());
        if (reason == null || reason.length() < 5 || reason.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("reason", "De 5 a 500 caracteres")));
        }
        if (r.state().equals("CANCELLED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Meta já cancelada.");
        }
        jdbc.sql("""
                        UPDATE goals SET state = 'CANCELLED', cancelled_by = :by, cancelled_at = now(), cancel_reason = :r,
                               version = version + 1 WHERE id = :id
                        """).param("by", actor.userId()).param("r", reason).param("id", id).update();
        audit.record(entry(actor, "goals.cancel", id, Map.of("reason", reason)));
        return get(actor, id);
    }

    // ---------------- progresso ----------------
    GoalView view(Actor actor, Row r, boolean closing) {
        LocalDate end = end(r.periodKind(), r.periodStart());
        Progress p = progress(actor.organizationId(), r, end, closing);
        String scale = r.metric().equals("SOLD_QUANTITY") ? r.target().setScale(6, RoundingMode.UNNECESSARY).toPlainString()
                : Decimals.moneyStr(r.target());
        return new GoalView(r.id(), r.metric(), metricLabel(r.metric()), r.unit(), r.periodKind(), r.periodStart(), end,
                reports.range(new ReportService.Filter(r.periodKind(), r.periodStart(), null, null, null)).label(), scale, r.title(),
                r.notes(), r.branchId(), r.branchName(), r.state(), r.cancelReason(), r.createdByName(), r.createdAt(),
                r.activatedByName(), r.activatedAt(), r.cancelledByName(), r.cancelledAt(), p, r.version());
    }

    Progress progress(UUID org, Row r, LocalDate end, boolean closing) {
        LocalDate today = reports.today();
        int total = (int) ChronoUnit.DAYS.between(r.periodStart(), end);
        int elapsed = today.isBefore(r.periodStart()) ? 0 : today.isBefore(end)
                ? (int) ChronoUnit.DAYS.between(r.periodStart(), today) + 1 : total;
        String formula = "Faltante = meta − realizado (mínimo 0); % = realizado ÷ meta × 100; ritmo esperado = meta × dias "
                + "decorridos ÷ dias do período (linear, só referência). Realizado = " + realizedFormula(r.metric(), r.unit()) + ".";
        BigDecimal expected = r.target().multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(total),
                r.metric().equals("SOLD_QUANTITY") ? 6 : 2, RoundingMode.HALF_UP);
        boolean qty = r.metric().equals("SOLD_QUANTITY");
        if (r.metric().equals("PROFIT") && !closing) {
            return new Progress(null, null, null, fmt(expected, qty), elapsed, total, "HIDDEN", formula);
        }
        ReportService.Totals t = reports.totals(org, r.periodStart(), end, r.branchId());
        BigDecimal realized = switch (r.metric()) {
            case "NET_REVENUE" -> t.netRevenue();
            case "PROFIT" -> t.profit();
            default -> t.soldByUnit().getOrDefault(r.unit(), BigDecimal.ZERO);
        };
        BigDecimal remaining = r.target().subtract(realized).max(BigDecimal.ZERO);
        BigDecimal pct = realized.multiply(BigDecimal.valueOf(100)).divide(r.target(), 2, RoundingMode.HALF_UP);
        String status;
        if (realized.compareTo(r.target()) >= 0) {
            status = "ACHIEVED";
        } else if (today.isBefore(r.periodStart())) {
            status = "NOT_STARTED";
        } else if (!today.isBefore(end)) {
            status = "MISSED";
        } else {
            status = realized.compareTo(expected) >= 0 ? "ON_PACE" : "BEHIND_PACE";
        }
        return new Progress(fmt(realized, qty), fmt(remaining, qty), pct.toPlainString(), fmt(expected, qty), elapsed, total,
                status, formula);
    }

    static String fmt(BigDecimal v, boolean qty) {
        return qty ? v.setScale(6, RoundingMode.HALF_UP).toPlainString() : Decimals.moneyStr(v);
    }

    static String realizedFormula(String metric, String unit) {
        return switch (metric) {
            case "NET_REVENUE" -> "vendas cumpridas − descontos − devoluções no período (competência)";
            case "PROFIT" -> "receita líquida − CMV − despesas − perdas no período (competência)";
            default -> "quantidade retirada/entregue − devolvida, em " + unit + ", no período";
        };
    }

    static String metricLabel(String metric) {
        return switch (metric) {
            case "NET_REVENUE" -> "Vendas (receita líquida)";
            case "PROFIT" -> "Lucro gerencial";
            default -> "Quantidade vendida";
        };
    }

    static LocalDate end(String kind, LocalDate start) {
        return kind.equals("WEEK") ? start.plusDays(7) : start.plusMonths(1);
    }

    // ---------------- apoio ----------------
    record Valid(String metric, String unit, String kind, LocalDate start, BigDecimal target, String title, String notes) {
    }

    Valid validate(Command c) {
        List<FieldError> errors = new ArrayList<>();
        if (c == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corpo obrigatório.");
        }
        String metric = c.metric() == null ? null : c.metric().trim().toUpperCase();
        if (metric == null || !METRICS.contains(metric)) {
            errors.add(new FieldError("metric", "Use NET_REVENUE, SOLD_QUANTITY ou PROFIT"));
        }
        String unit = Text.trim(c.unit());
        if ("SOLD_QUANTITY".equals(metric)) {
            if (unit == null || !unit.matches("^[A-Z_]{2,20}$")) {
                errors.add(new FieldError("unit", "Informe a unidade-base (ex.: DUZIA)"));
            }
        } else {
            unit = null;
        }
        String kind = c.period_kind() == null ? null : c.period_kind().trim().toUpperCase();
        if (!"WEEK".equals(kind) && !"MONTH".equals(kind)) {
            errors.add(new FieldError("period_kind", "Use WEEK ou MONTH"));
        }
        LocalDate start = c.period_start();
        if (start == null) {
            errors.add(new FieldError("period_start", "Obrigatório"));
        } else if ("WEEK".equals(kind) && start.getDayOfWeek() != DayOfWeek.MONDAY) {
            errors.add(new FieldError("period_start", "A semana começa na segunda-feira"));
        } else if ("MONTH".equals(kind) && start.getDayOfMonth() != 1) {
            errors.add(new FieldError("period_start", "O mês começa no dia 1"));
        }
        BigDecimal target = Decimals.parse(c.target(), "SOLD_QUANTITY".equals(metric) ? 6 : 2, false, "target", errors);
        String title = Text.trim(c.title());
        if (title == null || title.length() < 3 || title.length() > 120) {
            errors.add(new FieldError("title", "De 3 a 120 caracteres"));
        }
        String notes = Text.trim(c.notes());
        if (notes != null && notes.length() > 500) {
            errors.add(new FieldError("notes", "Até 500 caracteres"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija a meta.", errors);
        }
        return new Valid(metric, unit, kind, start, target, title, notes);
    }

    /** Meta da organização e na unidade permitida pelo escopo da ação; fora disso, 404. */
    Row visible(Actor actor, UUID id, String action) {
        Row r = jdbc.sql(SELECT + " WHERE g.id = :id AND g.organization_id = :o").param("id", id)
                .param("o", actor.organizationId()).query(Row.class).optional().orElseThrow(ApiException::notFound);
        ReportService.Access a = reports.access(actor, "goals", action, null);
        if (a.branch() != null && !a.branch().equals(r.branchId())) {
            throw ApiException.notFound();
        }
        return r;
    }

    Row locked(Actor actor, UUID id, String action) {
        visible(actor, id, action);
        jdbc.sql("SELECT id FROM goals WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).single();
        return jdbc.sql(SELECT + " WHERE g.id = :id").param("id", id).query(Row.class).single();
    }

    static void requireVersion(Row r, long version) {
        if (r.version() != version) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A meta mudou; recarregue.");
        }
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                "goal", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
