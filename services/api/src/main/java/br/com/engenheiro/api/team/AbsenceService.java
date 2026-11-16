package br.com.engenheiro.api.team;

import br.com.engenheiro.api.audit.AuditEntry;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folgas e férias (pedido do responsável, ADR-021). A "escala" é o calendário de disponibilidade: quem está fora em
 * cada dia e quantos sobram por setor; aprovar ausência que deixa um setor sem ninguém gera aviso (não bloqueia).
 * Duas ausências aprovadas da mesma pessoa não se sobrepõem. Registrar/aprovar: `users.edit_draft`; ver: `users.view`.
 * Ninguém aprova a própria ausência (exceto administrador). Regras de férias da CLT (período aquisitivo, abono,
 * pagamento) ficam com o contador e a folha (F07); aqui só o calendário e os dias já aprovados no ano.
 */
@Service
public class AbsenceService {
    static final ZoneId COMMERCIAL = ZoneId.of("America/Sao_Paulo");
    static final List<String> KINDS = List.of("VACATION", "DAY_OFF", "LEAVE");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;

    public AbsenceService(JdbcClient jdbc, AuthorizationService authz, AuditService audit) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
    }

    public record CreateCommand(UUID membership_id, String kind, LocalDate start_date, LocalDate end_date, String notes) {
    }

    public record NoteCommand(String note) {
    }

    public record AbsenceView(UUID id, UUID membership_id, UUID user_id, String person, String sector, String branch,
                              String kind, LocalDate start_date, LocalDate end_date, long days, String state, String notes,
                              String created_by_name, OffsetDateTime created_at, String decided_by_name,
                              String decided_note, long version, List<String> warnings) {
    }

    public record Member(UUID membership_id, UUID user_id, String person, String sector, String role_template,
                         String branch, long vacation_days_last_12m) {
    }

    public record SectorDay(String sector, int total, int available, List<String> absent, boolean uncovered) {
    }

    public record Day(LocalDate date, List<SectorDay> sectors) {
    }

    public record Calendar(LocalDate from, LocalDate to, List<Member> members, List<Day> days, List<AbsenceView> absences) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, UUID membershipId, UUID userId, String person, String sector,
               String branch, String kind, LocalDate startDate, LocalDate endDate, String state, String notes,
               String createdByName, OffsetDateTime createdAt, String decidedByName, String decidedNote, long version) {
    }

    static final String SELECT = """
            SELECT a.id, a.organization_id, a.branch_id, a.membership_id, m.user_id, u.display_name AS person, m.sector,
                   b.name AS branch, a.kind, a.start_date, a.end_date, a.state, a.notes, c.display_name AS created_by_name,
                   a.created_at, d.display_name AS decided_by_name, a.decided_note, a.version
              FROM employee_absences a JOIN memberships m ON m.id = a.membership_id JOIN app_users u ON u.id = m.user_id
              JOIN branches b ON b.id = a.branch_id JOIN app_users c ON c.id = a.created_by
              LEFT JOIN app_users d ON d.id = a.decided_by
            """;

    @Transactional
    public AbsenceView create(Actor actor, CreateCommand cmd) {
        authz.require(actor, "users", "edit_draft");
        List<FieldError> errors = new ArrayList<>();
        record M(UUID id, UUID branchId, UUID userId, String status) {
        }
        M m = cmd == null || cmd.membership_id() == null ? null : jdbc.sql("""
                        SELECT id, branch_id, user_id, status FROM memberships WHERE id = :id AND organization_id = :o
                        """).param("id", cmd.membership_id()).param("o", actor.organizationId()).query(M.class).optional()
                .orElse(null);
        if (m == null || !m.status().equals("ACTIVE")) {
            errors.add(new FieldError("membership_id", "Funcionário não encontrado ou inativo"));
        }
        if (cmd == null || cmd.kind() == null || !KINDS.contains(cmd.kind())) {
            errors.add(new FieldError("kind", "Use férias, folga ou afastamento"));
        }
        LocalDate today = LocalDate.now(COMMERCIAL);
        if (cmd == null || cmd.start_date() == null || cmd.end_date() == null) {
            errors.add(new FieldError("start_date", "Informe início e fim"));
        } else {
            if (cmd.end_date().isBefore(cmd.start_date())) {
                errors.add(new FieldError("end_date", "Fim antes do início"));
            } else if (ChronoUnit.DAYS.between(cmd.start_date(), cmd.end_date()) >= 60) {
                errors.add(new FieldError("end_date", "Até 60 dias por registro"));
            }
            if (cmd.start_date().isBefore(today.minusDays(90)) || cmd.start_date().isAfter(today.plusYears(1))) {
                errors.add(new FieldError("start_date", "Data fora do intervalo permitido"));
            }
        }
        String notes = Text.trim(cmd == null ? null : cmd.notes());
        if (notes != null && notes.length() > 500) {
            errors.add(new FieldError("notes", "Até 500 caracteres"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                        INSERT INTO employee_absences (id, organization_id, branch_id, membership_id, kind, start_date, end_date,
                                                       notes, created_by)
                        VALUES (:id, :o, :b, :m, :k, :s, :e, :n, :by)
                        """).param("id", id).param("o", actor.organizationId()).param("b", m.branchId()).param("m", m.id())
                .param("k", cmd.kind()).param("s", cmd.start_date()).param("e", cmd.end_date()).param("n", notes)
                .param("by", actor.userId()).update();
        audit.record(entry(actor, "team.absence.create", id, Map.of("kind", cmd.kind(), "start", cmd.start_date().toString(),
                "end", cmd.end_date().toString())));
        return view(find(id).orElseThrow());
    }

    /** Aprovar: sem sobreposição com outra ausência aprovada da pessoa; avisos de setor descoberto voltam na resposta. */
    @Transactional
    public AbsenceView approve(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row a = lock(actor, id);
        if (a.userId().equals(actor.userId()) && !actor.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Ninguém aprova a própria ausência.");
        }
        if (!a.state().equals("REQUESTED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Ausência já decidida (" + a.state() + ").");
        }
        jdbc.sql("SELECT id FROM memberships WHERE id = :m FOR UPDATE").param("m", a.membershipId()).query(UUID.class).single();
        Optional<String> overlap = jdbc.sql("""
                        SELECT start_date || ' a ' || end_date FROM employee_absences
                         WHERE membership_id = :m AND state = 'APPROVED' AND id <> :id
                           AND start_date <= :e AND end_date >= :s LIMIT 1
                        """).param("m", a.membershipId()).param("id", a.id()).param("s", a.startDate()).param("e", a.endDate())
                .query(String.class).optional();
        if (overlap.isPresent()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, a.person() + " já tem ausência aprovada em " + overlap.get() + ".");
        }
        decide(actor, a, expectedVersion, "APPROVED", Text.trim(cmd == null ? null : cmd.note()));
        audit.record(entry(actor, "team.absence.approve", a.id(), Map.of("person", a.person())));
        AbsenceView v = view(find(id).orElseThrow());
        return new AbsenceView(v.id(), v.membership_id(), v.user_id(), v.person(), v.sector(), v.branch(), v.kind(),
                v.start_date(), v.end_date(), v.days(), v.state(), v.notes(), v.created_by_name(), v.created_at(),
                v.decided_by_name(), v.decided_note(), v.version(), coverageWarnings(actor.organizationId(), a));
    }

    @Transactional
    public AbsenceView reject(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row a = lock(actor, id);
        String note = Text.trim(cmd == null ? null : cmd.note());
        if (note == null || note.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("note", "Obrigatório")));
        }
        if (!a.state().equals("REQUESTED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Ausência já decidida (" + a.state() + ").");
        }
        decide(actor, a, expectedVersion, "REJECTED", note);
        audit.record(entry(actor, "team.absence.reject", a.id(), Map.of("reason", note)));
        return view(find(id).orElseThrow());
    }

    @Transactional
    public AbsenceView cancel(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row a = lock(actor, id);
        String note = Text.trim(cmd == null ? null : cmd.note());
        if (note == null || note.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("note", "Obrigatório")));
        }
        if (a.state().equals("CANCELLED") || a.state().equals("REJECTED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Ausência já encerrada.");
        }
        int n = jdbc.sql("""
                        UPDATE employee_absences SET state = 'CANCELLED', decided_by = :by, decided_at = now(), decided_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("n", note).param("id", a.id()).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A ausência foi alterada por outra pessoa. Recarregue.");
        }
        audit.record(entry(actor, "team.absence.cancel", a.id(), Map.of("reason", note)));
        return view(find(id).orElseThrow());
    }

    /** Calendário: por dia e setor, total ativo, disponíveis e quem está fora (só ausências aprovadas). */
    @Transactional(readOnly = true)
    public Calendar calendar(Actor actor, LocalDate from, LocalDate to) {
        authz.require(actor, "users", "view");
        LocalDate f = from == null ? LocalDate.now(COMMERCIAL).withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1) : to;
        if (!t.isAfter(f) || ChronoUnit.DAYS.between(f, t) > 93) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Intervalo inválido (fim exclusivo, até 93 dias).");
        }
        LocalDate today = LocalDate.now(COMMERCIAL);
        List<Member> members = jdbc.sql("""
                        SELECT m.id AS membership_id, m.user_id, u.display_name AS person, m.sector, m.role_template, b.name AS branch,
                               coalesce((SELECT sum(least(a.end_date, :today) - greatest(a.start_date, :since) + 1)
                                           FROM employee_absences a WHERE a.membership_id = m.id AND a.kind = 'VACATION'
                                            AND a.state = 'APPROVED' AND a.end_date >= :since AND a.start_date <= :today), 0)
                                 AS vacation_days_last_12m
                          FROM memberships m JOIN app_users u ON u.id = m.user_id JOIN branches b ON b.id = m.branch_id
                         WHERE m.organization_id = :o AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
                         ORDER BY m.sector, u.display_name
                        """).param("o", actor.organizationId()).param("today", today).param("since", today.minusYears(1))
                .query(Member.class).list();
        List<AbsenceView> absences = jdbc.sql(SELECT + """
                         WHERE a.organization_id = :o AND a.state IN ('REQUESTED', 'APPROVED')
                           AND a.start_date < :t AND a.end_date >= :f ORDER BY a.start_date, u.display_name
                        """).param("o", actor.organizationId()).param("f", f).param("t", t).query(Row.class).list()
                .stream().map(this::view).toList();
        List<Day> days = new ArrayList<>();
        for (LocalDate d = f; d.isBefore(t); d = d.plusDays(1)) {
            Map<String, List<String>> absent = new LinkedHashMap<>();
            Map<String, Integer> total = new LinkedHashMap<>();
            for (Member m : members) {
                total.merge(m.sector(), 1, Integer::sum);
                absent.putIfAbsent(m.sector(), new ArrayList<>());
            }
            for (AbsenceView a : absences) {
                if (a.state().equals("APPROVED") && !d.isBefore(a.start_date()) && !d.isAfter(a.end_date())) {
                    absent.computeIfAbsent(a.sector(), k -> new ArrayList<>()).add(a.person() + " (" + label(a.kind()) + ")");
                }
            }
            List<SectorDay> sectors = new ArrayList<>();
            for (Map.Entry<String, Integer> e : total.entrySet()) {
                List<String> out = absent.get(e.getKey());
                int available = e.getValue() - out.size();
                sectors.add(new SectorDay(e.getKey(), e.getValue(), available, out, available <= 0));
            }
            days.add(new Day(d, sectors));
        }
        return new Calendar(f, t, members, days, absences);
    }

    // ---------------- apoio ----------------
    private List<String> coverageWarnings(UUID org, Row a) {
        List<String> warnings = new ArrayList<>();
        int total = jdbc.sql("""
                        SELECT count(*) FROM memberships m JOIN app_users u ON u.id = m.user_id
                         WHERE m.organization_id = :o AND m.sector = :s AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
                        """).param("o", org).param("s", a.sector()).query(Integer.class).single();
        for (LocalDate d = a.startDate(); !d.isAfter(a.endDate()); d = d.plusDays(1)) {
            int out = jdbc.sql("""
                            SELECT count(DISTINCT a.membership_id) FROM employee_absences a JOIN memberships m ON m.id = a.membership_id
                             WHERE a.organization_id = :o AND m.sector = :s AND a.state = 'APPROVED'
                               AND a.start_date <= :d AND a.end_date >= :d
                            """).param("o", org).param("s", a.sector()).param("d", d).query(Integer.class).single();
            if (total - out <= 0) {
                warnings.add(d + ": setor " + a.sector() + " sem ninguém disponível");
            }
        }
        return warnings;
    }

    private void decide(Actor actor, Row a, long expectedVersion, String state, String note) {
        int n = jdbc.sql("""
                        UPDATE employee_absences SET state = :s, decided_by = :by, decided_at = now(), decided_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("s", state).param("by", actor.userId()).param("n", note).param("id", a.id())
                .param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A ausência foi alterada por outra pessoa. Recarregue.");
        }
    }

    private Row lock(Actor actor, UUID id) {
        authz.require(actor, "users", "edit_draft");
        jdbc.sql("SELECT id FROM employee_absences WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return find(id).filter(r -> r.organizationId().equals(actor.organizationId())).orElseThrow(ApiException::notFound);
    }

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query(Row.class).optional();
    }

    private AbsenceView view(Row r) {
        return new AbsenceView(r.id(), r.membershipId(), r.userId(), r.person(), r.sector(), r.branch(), r.kind(),
                r.startDate(), r.endDate(), ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1, r.state(), r.notes(),
                r.createdByName(), r.createdAt(), r.decidedByName(), r.decidedNote(), r.version(), List.of());
    }

    static String label(String kind) {
        return switch (kind) {
            case "VACATION" -> "férias";
            case "DAY_OFF" -> "folga";
            default -> "afastamento";
        };
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                "employee_absence", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
