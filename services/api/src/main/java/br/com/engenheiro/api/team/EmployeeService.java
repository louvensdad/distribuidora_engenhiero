package br.com.engenheiro.api.team;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AccessAdministrationService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.Effect;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Cursor;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import br.com.engenheiro.api.shared.web.SearchText;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de funcionários (ADR-027). O funcionário existe sem conta de login; a conta é criada depois no provedor
 * de identidade (ou ligada a uma existente). Campos protegidos: telefone (operational_contact), CPF/nascimento/
 * endereço (personal_document) e salário (employee_salary) — mascarados para quem não vê e recusados na gravação.
 * Ver: users.view (escopo de unidade); manter: users.edit_draft; dar acesso: também permissions.edit_draft.
 */
@Service
public class EmployeeService {
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("e.organization_id", "e.branch_id", null, "e.created_by", null);

    public record Account(UUID user_id, String display_name, String status, String role_template, String sector) {
    }

    public record EmployeeView(UUID id, String full_name, UUID branch_id, String branch, UUID job_position_id,
                               String job_position, String sector, String duties, LocalDate admission_date,
                               LocalDate termination_date, String status, String work_schedule, String phone, String cpf,
                               LocalDate birth_date, String address, String base_salary, String notes, Account account,
                               long version, OffsetDateTime created_at, String created_by_name, List<String> masked_fields) {
    }

    public record Page(List<EmployeeView> items, String next_cursor) {
    }

    /** Campos opcionais em branco = sem valor. Datas ISO (AAAA-MM-DD). Valores em decimal com ponto. */
    public record Command(String full_name, UUID branch_id, UUID job_position_id, String duties, String admission_date,
                          String status, String work_schedule, String phone, String cpf, String birth_date, String address,
                          String base_salary, String notes) {
    }

    public record TerminationCommand(String termination_date, String reason) {
    }

    public record AccessCommand(String username, String role_template) {
    }

    public record AccessResult(EmployeeView employee, String username, String temporary_password, String provider,
                               String note) {
    }

    public record LinkCommand(UUID user_id) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String branchName, String fullName, UUID jobPositionId,
               String jobPosition, String sector, String duties, LocalDate admissionDate, LocalDate terminationDate,
               String status, String workSchedule, String phone, String cpf, LocalDate birthDate, String address,
               BigDecimal baseSalary, String notes, UUID membershipId, UUID accountUserId, String accountName,
               String accountStatus, String accountTemplate, String accountSector, long version, OffsetDateTime createdAt,
               UUID createdBy, String createdByName) {
    }

    private static final String SELECT = """
            SELECT e.id, e.organization_id, e.branch_id, b.name AS branch_name, e.full_name, e.job_position_id,
                   p.name AS job_position, p.sector, e.duties, e.admission_date, e.termination_date, e.status,
                   e.work_schedule, e.phone, e.cpf, e.birth_date, e.address, e.base_salary, e.notes, e.membership_id,
                   m.user_id AS account_user_id, u.display_name AS account_name,
                   CASE WHEN m.id IS NULL THEN NULL WHEN m.status = 'ACTIVE' AND u.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'DISABLED' END AS account_status,
                   m.role_template AS account_template, m.sector AS account_sector,
                   e.version, e.created_at, e.created_by, cb.display_name AS created_by_name
              FROM employees e JOIN branches b ON b.id = e.branch_id JOIN job_positions p ON p.id = e.job_position_id
              JOIN app_users cb ON cb.id = e.created_by
              LEFT JOIN memberships m ON m.id = e.membership_id LEFT JOIN app_users u ON u.id = m.user_id
            """;

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdentityRepository identity;
    private final AccessAdministrationService access;
    private final ObjectProvider<IdentityProvisioner> provisioners;

    public EmployeeService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdentityRepository identity,
                           AccessAdministrationService access, ObjectProvider<IdentityProvisioner> provisioners) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.identity = identity;
        this.access = access;
        this.provisioners = provisioners;
    }

    /** Provedor de contas configurado (o primeiro), ou null = NÃO CONFIGURADO. */
    public IdentityProvisioner provisioner() {
        return provisioners.orderedStream().filter(IdentityProvisioner::configured).findFirst().orElse(null);
    }

    // ---------------- leitura ----------------
    @Transactional(readOnly = true)
    public Page list(Actor actor, String query, String status, UUID positionId, int limit, String cursor) {
        Decision d = authz.require(actor, "users", "view");
        Cursor.checkLimit(limit);
        if (status != null && !List.of("ACTIVE", "ON_LEAVE", "TERMINATED").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "status inválido.");
        }
        String q = SearchText.like(SearchText.normalize(query));
        ScopeSql.Predicate p = ScopeSql.of(actor, d.scopes(), COLUMNS);
        Cursor.Key c = Cursor.decode(cursor);
        var spec = jdbc.sql(SELECT + " WHERE " + p.sql()
                        + " AND (CAST(:q AS text) IS NULL OR e.full_name ILIKE '%' || CAST(:q AS text) || '%' ESCAPE '\\')"
                        + " AND (CAST(:st AS text) IS NULL OR e.status = CAST(:st AS text))"
                        + " AND (CAST(:pos AS uuid) IS NULL OR e.job_position_id = CAST(:pos AS uuid))"
                        + " AND (CAST(:cs AS text) IS NULL OR (e.full_name, e.id) > (CAST(:cs AS text), CAST(:cid AS uuid)))"
                        + " ORDER BY e.full_name, e.id LIMIT :lim")
                .param("q", q).param("st", status).param("pos", positionId)
                .param("cs", c == null ? null : c.sort()).param("cid", c == null ? null : c.id()).param("lim", limit + 1);
        for (Map.Entry<String, Object> e : p.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        List<Row> rows = spec.query(Row.class).list();
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            next = Cursor.encode(rows.getLast().fullName(), rows.getLast().id());
        }
        return new Page(rows.stream().map(r -> view(actor, r)).toList(), next);
    }

    @Transactional(readOnly = true)
    public EmployeeView get(Actor actor, UUID id) {
        return view(actor, requireRow(actor, id, "view"));
    }

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id").param("id", id).query(Row.class).optional();
    }

    private Row requireRow(Actor actor, UUID id, String action) {
        Row r = find(id).orElse(null);
        if (r == null) {
            authz.require(actor, "users", action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, "users", action, "employee", id.toString(),
                new RecordRef(r.organizationId(), r.branchId(), null, r.createdBy(), null));
        return r;
    }

    private EmployeeView view(Actor actor, Row r) {
        List<String> masked = new ArrayList<>();
        boolean contact = authz.canSeeField(actor, FieldClass.OPERATIONAL_CONTACT);
        boolean personal = authz.canSeeField(actor, FieldClass.PERSONAL_DOCUMENT);
        boolean salary = authz.canSeeField(actor, FieldClass.EMPLOYEE_SALARY);
        if (!contact) {
            masked.add("phone");
        }
        if (!personal) {
            masked.addAll(List.of("cpf", "birth_date", "address"));
        }
        if (!salary) {
            masked.add("base_salary");
        }
        Account acc = r.membershipId() == null ? null
                : new Account(r.accountUserId(), r.accountName(), r.accountStatus(), r.accountTemplate(), r.accountSector());
        return new EmployeeView(r.id(), r.fullName(), r.branchId(), r.branchName(), r.jobPositionId(), r.jobPosition(),
                r.sector(), r.duties(), r.admissionDate(), r.terminationDate(), r.status(), r.workSchedule(),
                contact ? r.phone() : null, personal ? r.cpf() : null, personal ? r.birthDate() : null,
                personal ? r.address() : null, salary && r.baseSalary() != null ? r.baseSalary().setScale(2).toPlainString() : null,
                r.notes(), acc, r.version(), r.createdAt(), r.createdByName(), masked);
    }

    // ---------------- gravação ----------------
    private record Clean(String fullName, UUID branchId, UUID positionId, String duties, LocalDate admission, String status,
                         String schedule, String phone, String cpf, LocalDate birth, String address, BigDecimal salary,
                         String notes) {
    }

    private static LocalDate date(String v, String field, List<FieldError> errors) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.strip());
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(field, "Data inválida (AAAA-MM-DD)"));
            return null;
        }
    }

    private static String opt(String v, int max, String field, List<FieldError> errors) {
        String t = Text.trim(v);
        if (t != null && t.length() > max) {
            errors.add(new FieldError(field, "Até " + max + " caracteres"));
        }
        return t;
    }

    private void rejectHidden(Actor actor, FieldClass f, Object value, String field, List<FieldError> errors) {
        if (value != null && !authz.canSeeField(actor, f)) {
            errors.add(new FieldError(field, "Você não tem permissão para informar este campo"));
        }
    }

    private Clean validate(Actor actor, Command c, Row current) {
        if (c == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corpo obrigatório.");
        }
        List<FieldError> errors = new ArrayList<>();
        String name = Text.trim(c.full_name());
        if (name != null) {
            name = name.replaceAll("\\s+", " ");
        }
        if (name == null || name.length() > 160) {
            errors.add(new FieldError("full_name", "Nome completo obrigatório (até 160 caracteres)"));
        }
        UUID branch = c.branch_id() != null ? c.branch_id() : current != null ? current.branchId() : actor.branchId();
        if (jdbc.sql("SELECT count(*) FROM branches WHERE id = :b AND organization_id = :o").param("b", branch)
                .param("o", actor.organizationId()).query(Long.class).single() == 0) {
            errors.add(new FieldError("branch_id", "Unidade inexistente"));
        }
        UUID pos = c.job_position_id();
        if (pos == null) {
            errors.add(new FieldError("job_position_id", "Escolha o cargo"));
        } else {
            String st = jdbc.sql("SELECT status FROM job_positions WHERE id = :p AND organization_id = :o").param("p", pos)
                    .param("o", actor.organizationId()).query(String.class).optional().orElse(null);
            boolean unchanged = current != null && pos.equals(current.jobPositionId());
            if (st == null || (st.equals("ARCHIVED") && !unchanged)) {
                errors.add(new FieldError("job_position_id", "Cargo inexistente ou arquivado"));
            }
        }
        LocalDate admission = date(c.admission_date(), "admission_date", errors);
        if (admission == null && errors.stream().noneMatch(e -> e.field().equals("admission_date"))) {
            errors.add(new FieldError("admission_date", "Data de admissão obrigatória"));
        }
        String status = c.status() == null || c.status().isBlank() ? (current == null ? "ACTIVE" : current.status()) : c.status();
        if (current != null && current.status().equals("TERMINATED") && !status.equals("TERMINATED")) {
            errors.add(new FieldError("status", "Funcionário desligado não volta a ativo por edição"));
        } else if (!status.equals(current == null ? "ACTIVE" : current.status()) && !List.of("ACTIVE", "ON_LEAVE").contains(status)) {
            errors.add(new FieldError("status", "ACTIVE ou ON_LEAVE (desligamento tem fluxo próprio)"));
        }
        String phone = opt(c.phone(), 40, "phone", errors);
        String cpf = Text.document(c.cpf());
        if (cpf != null && cpf.isEmpty()) {
            cpf = null;
        }
        if (cpf != null && !cpf.matches("[0-9]{11}")) {
            errors.add(new FieldError("cpf", "CPF com 11 dígitos"));
        }
        LocalDate birth = date(c.birth_date(), "birth_date", errors);
        String address = opt(c.address(), 300, "address", errors);
        BigDecimal salary = null;
        String s = Text.trim(c.base_salary());
        if (s != null) {
            try {
                salary = new BigDecimal(s);
                if (salary.signum() < 0 || salary.scale() > 2) {
                    errors.add(new FieldError("base_salary", "Valor não negativo com até 2 casas"));
                }
            } catch (NumberFormatException e) {
                errors.add(new FieldError("base_salary", "Valor inválido (ex.: 2500.00)"));
            }
        }
        String duties = opt(c.duties(), 500, "duties", errors);
        String schedule = opt(c.work_schedule(), 120, "work_schedule", errors);
        String notes = opt(c.notes(), 1000, "notes", errors);
        rejectHidden(actor, FieldClass.OPERATIONAL_CONTACT, phone, "phone", errors);
        rejectHidden(actor, FieldClass.PERSONAL_DOCUMENT, cpf, "cpf", errors);
        rejectHidden(actor, FieldClass.PERSONAL_DOCUMENT, birth, "birth_date", errors);
        rejectHidden(actor, FieldClass.PERSONAL_DOCUMENT, address, "address", errors);
        rejectHidden(actor, FieldClass.EMPLOYEE_SALARY, salary, "base_salary", errors);
        if (birth != null && admission != null && !birth.isBefore(admission.minusYears(14))) {
            errors.add(new FieldError("birth_date", "Data de nascimento incompatível com a admissão"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        // Campo oculto mantém o valor gravado (quem não vê não apaga às cegas).
        if (current != null) {
            if (!authz.canSeeField(actor, FieldClass.OPERATIONAL_CONTACT)) {
                phone = current.phone();
            }
            if (!authz.canSeeField(actor, FieldClass.PERSONAL_DOCUMENT)) {
                cpf = current.cpf();
                birth = current.birthDate();
                address = current.address();
            }
            if (!authz.canSeeField(actor, FieldClass.EMPLOYEE_SALARY)) {
                salary = current.baseSalary();
            }
        }
        return new Clean(name, branch, pos, duties, admission, status, schedule, phone, cpf, birth, address, salary, notes);
    }

    private static ApiException duplicateCpf() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "CPF já cadastrado para outro funcionário.",
                List.of(new FieldError("cpf", "Já existe funcionário com este CPF")));
    }

    @Transactional
    public EmployeeView create(Actor actor, Command cmd) {
        authz.require(actor, "users", "edit_draft");
        Clean c = validate(actor, cmd, null);
        authz.requireRecord(actor, "users", "edit_draft", "employee", "novo",
                new RecordRef(actor.organizationId(), c.branchId(), null, actor.userId(), null));
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO employees (id, organization_id, branch_id, full_name, job_position_id, duties,
                                admission_date, status, work_schedule, phone, cpf, birth_date, address, base_salary, notes, created_by)
                            VALUES (:id, :o, :b, :n, :p, :d, :adm, :st, :ws, :ph, :cpf, :bd, :ad, :sal, :nt, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("b", c.branchId()).param("n", c.fullName())
                    .param("p", c.positionId()).param("d", c.duties()).param("adm", c.admission()).param("st", c.status())
                    .param("ws", c.schedule()).param("ph", c.phone()).param("cpf", c.cpf()).param("bd", c.birth())
                    .param("ad", c.address()).param("sal", c.salary()).param("nt", c.notes()).param("by", actor.userId()).update();
        } catch (DuplicateKeyException e) {
            throw duplicateCpf();
        }
        audit.record(new AuditEntry(actor.organizationId(), c.branchId(), actor.userId(), "USER", actor.clientId(),
                "employees.create", "employee", id.toString(), "SUCCESS", null, Map.of("fields_set", fieldsSet(c)), "api"));
        return view(actor, find(id).orElseThrow());
    }

    private static List<String> fieldsSet(Clean c) {
        List<String> f = new ArrayList<>(List.of("full_name", "job_position_id", "admission_date"));
        Map<String, Object> opt = new HashMap<>();
        opt.put("duties", c.duties());
        opt.put("work_schedule", c.schedule());
        opt.put("phone", c.phone());
        opt.put("cpf", c.cpf());
        opt.put("birth_date", c.birth());
        opt.put("address", c.address());
        opt.put("base_salary", c.salary());
        opt.put("notes", c.notes());
        opt.forEach((k, v) -> {
            if (v != null) {
                f.add(k);
            }
        });
        return f;
    }

    @Transactional
    public EmployeeView update(Actor actor, UUID id, long expectedVersion, Command cmd) {
        Row cur = requireRow(actor, id, "edit_draft");
        Clean c = validate(actor, cmd, cur);
        if (!c.branchId().equals(cur.branchId())) {
            authz.requireRecord(actor, "users", "edit_draft", "employee", id.toString(),
                    new RecordRef(actor.organizationId(), c.branchId(), null, cur.createdBy(), null));
        }
        int n;
        try {
            n = jdbc.sql("""
                            UPDATE employees SET full_name = :n, branch_id = :b, job_position_id = :p, duties = :d,
                                   admission_date = :adm, status = :st, work_schedule = :ws, phone = :ph, cpf = :cpf,
                                   birth_date = :bd, address = :ad, base_salary = :sal, notes = :nt,
                                   version = version + 1, updated_at = now(), updated_by = :by
                             WHERE id = :id AND version = :v
                            """).param("n", c.fullName()).param("b", c.branchId()).param("p", c.positionId())
                    .param("d", c.duties()).param("adm", c.admission()).param("st", c.status()).param("ws", c.schedule())
                    .param("ph", c.phone()).param("cpf", c.cpf()).param("bd", c.birth()).param("ad", c.address())
                    .param("sal", c.salary()).param("nt", c.notes()).param("by", actor.userId()).param("id", id)
                    .param("v", expectedVersion).update();
        } catch (DuplicateKeyException e) {
            throw duplicateCpf();
        }
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O cadastro foi alterado por outra pessoa. Recarregue.");
        }
        List<String> changed = new ArrayList<>();
        compare(changed, "full_name", cur.fullName(), c.fullName());
        compare(changed, "branch_id", cur.branchId(), c.branchId());
        compare(changed, "job_position_id", cur.jobPositionId(), c.positionId());
        compare(changed, "duties", cur.duties(), c.duties());
        compare(changed, "admission_date", cur.admissionDate(), c.admission());
        compare(changed, "status", cur.status(), c.status());
        compare(changed, "work_schedule", cur.workSchedule(), c.schedule());
        compare(changed, "phone", cur.phone(), c.phone());
        compare(changed, "cpf", cur.cpf(), c.cpf());
        compare(changed, "birth_date", cur.birthDate(), c.birth());
        compare(changed, "address", cur.address(), c.address());
        compare(changed, "base_salary", cur.baseSalary() == null ? null : cur.baseSalary().stripTrailingZeros(),
                c.salary() == null ? null : c.salary().stripTrailingZeros());
        compare(changed, "notes", cur.notes(), c.notes());
        // Só NOMES de campo na trilha (nunca CPF, endereço ou salário).
        audit.record(new AuditEntry(actor.organizationId(), c.branchId(), actor.userId(), "USER", actor.clientId(),
                "employees.update", "employee", id.toString(), "SUCCESS", null, Map.of("fields_changed", changed), "api"));
        return view(actor, find(id).orElseThrow());
    }

    private static void compare(List<String> changed, String field, Object a, Object b) {
        if (!Objects.equals(a, b)) {
            changed.add(field);
        }
    }

    /** Desligamento: registra a data e bloqueia na hora a conta de login ligada (SEC-05). */
    @Transactional
    public EmployeeView terminate(Actor actor, UUID id, long expectedVersion, TerminationCommand cmd) {
        Row cur = requireRow(actor, id, "edit_draft");
        if (cur.status().equals("TERMINATED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Funcionário já desligado.");
        }
        List<FieldError> errors = new ArrayList<>();
        LocalDate date = date(cmd == null ? null : cmd.termination_date(), "termination_date", errors);
        if (date == null && errors.isEmpty()) {
            errors.add(new FieldError("termination_date", "Data do desligamento obrigatória"));
        }
        if (date != null && date.isBefore(cur.admissionDate())) {
            errors.add(new FieldError("termination_date", "Antes da admissão"));
        }
        String reason = cmd == null ? null : Text.trim(cmd.reason());
        if (reason == null || reason.length() > 500) {
            errors.add(new FieldError("reason", "Motivo obrigatório (até 500 caracteres)"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        if (cur.accountUserId() != null && cur.accountUserId().equals(actor.userId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Ninguém registra o próprio desligamento.");
        }
        int n = jdbc.sql("""
                        UPDATE employees SET status = 'TERMINATED', termination_date = :d, version = version + 1,
                               updated_at = now(), updated_by = :by WHERE id = :id AND version = :v
                        """).param("d", date).param("by", actor.userId()).param("id", id).param("v", expectedVersion).update();
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "O cadastro foi alterado por outra pessoa. Recarregue.");
        }
        boolean blocked = false;
        if (cur.accountUserId() != null && "ACTIVE".equals(cur.accountStatus())) {
            var m = identity.findMembership(actor.organizationId(), cur.accountUserId()).orElseThrow();
            access.setStatus(actor, cur.accountUserId(), m.version(), "DISABLED", "Desligamento: " + reason);
            blocked = true;
        }
        audit.record(new AuditEntry(actor.organizationId(), cur.branchId(), actor.userId(), "USER", actor.clientId(),
                "employees.terminate", "employee", id.toString(), "SUCCESS", reason,
                Map.of("termination_date", date.toString(), "account_blocked", blocked), "api"));
        return view(actor, find(id).orElseThrow());
    }

    /**
     * Dar acesso aos apps: cria a conta individual no provedor de identidade e o vínculo com o modelo escolhido.
     * Exige users.edit_draft e permissions.edit_draft; nunca administrador por aqui; ninguém concede o que não tem.
     */
    @Transactional
    public AccessResult grantAccess(Actor actor, UUID id, AccessCommand cmd) {
        Row cur = requireRow(actor, id, "edit_draft");
        authz.require(actor, "permissions", "edit_draft");
        if (cur.status().equals("TERMINATED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Funcionário desligado não recebe acesso.");
        }
        if (cur.membershipId() != null) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Este funcionário já tem conta de acesso.");
        }
        List<FieldError> errors = new ArrayList<>();
        String username = cmd == null || cmd.username() == null ? null : cmd.username().strip().toLowerCase();
        if (username == null || !username.matches("[a-z0-9][a-z0-9._-]{2,39}")) {
            errors.add(new FieldError("username", "3 a 40 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado"));
        }
        RoleTemplate template = null;
        try {
            template = cmd == null || cmd.role_template() == null ? null : RoleTemplate.valueOf(cmd.role_template());
        } catch (IllegalArgumentException e) {
            template = null;
        }
        if (template == null || template.admin()) {
            errors.add(new FieldError("role_template", "Escolha um modelo de acesso (administrador não é concedido por aqui)"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        for (RoleTemplate.TemplateGrant g : template.grants()) {
            if (!authz.decide(actor, g.resource(), g.action()).allowed()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Você não pode conceder " + g.resource() + "." + g.action() + ".");
            }
        }
        IdentityProvisioner prov = provisioner();
        if (prov == null) {
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE,
                    "Criação de conta NÃO CONFIGURADA: falta a credencial de serviço do provedor de identidade "
                            + "(ENGENHEIRO_PROVISIONER_CLIENT_ID/SECRET). O cadastro do funcionário continua valendo.");
        }
        IdentityProvisioner.Provisioned p;
        try {
            p = prov.createUser(username, cur.fullName(), id.toString());
        } catch (IdentityProvisioner.UsernameTaken e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Nome de usuário já em uso.",
                    List.of(new FieldError("username", "Já existe uma conta com esse nome de usuário")));
        } catch (IdentityProvisioner.Unavailable e) {
            throw new ApiException(ErrorCode.INTEGRATION_UNAVAILABLE,
                    "Provedor de identidade indisponível: nenhuma conta foi ligada. Tente de novo (não duplica).");
        }
        UUID userId = jdbc.sql("SELECT id FROM app_users WHERE issuer = :i AND external_subject = :s").param("i", p.issuer())
                .param("s", p.subject()).query(UUID.class).optional().orElse(null);
        if (userId == null) {
            userId = Ids.newId();
            jdbc.sql("INSERT INTO app_users (id, issuer, external_subject, display_name, status) VALUES (:id, :i, :s, :n, 'ACTIVE')")
                    .param("id", userId).param("i", p.issuer()).param("s", p.subject()).param("n", cur.fullName()).update();
        }
        if (jdbc.sql("SELECT count(*) FROM memberships WHERE user_id = :u AND organization_id = :o").param("u", userId)
                .param("o", actor.organizationId()).query(Long.class).single() > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Essa conta já está vinculada à empresa.");
        }
        UUID membership = Ids.newId();
        jdbc.sql("""
                        INSERT INTO memberships (id, user_id, organization_id, branch_id, sector, role_template, is_admin, status, created_by)
                        VALUES (:id, :u, :o, :b, :s, :t, false, 'ACTIVE', :by)
                        """).param("id", membership).param("u", userId).param("o", actor.organizationId()).param("b", cur.branchId())
                .param("s", template.sector().name()).param("t", template.name()).param("by", actor.userId()).update();
        identity.replaceClients(membership, template.clients());
        for (RoleTemplate.TemplateGrant g : template.grants()) {
            identity.insertGrant(Ids.newId(), membership, g.resource(), g.action(), g.scope(), Effect.ALLOW, null, null, actor.userId());
        }
        final UUID m = membership;
        template.fields().forEach((f, e) -> identity.insertFieldPolicy(Ids.newId(), m, f, e, actor.userId()));
        jdbc.sql("UPDATE employees SET membership_id = :m, version = version + 1, updated_at = now(), updated_by = :by WHERE id = :id")
                .param("m", membership).param("by", actor.userId()).param("id", id).update();
        audit.record(new AuditEntry(actor.organizationId(), cur.branchId(), actor.userId(), "USER", actor.clientId(),
                "employees.access_granted", "employee", id.toString(), "SUCCESS", null,
                Map.of("username", username, "template", template.name(), "provider", prov.name()), "api"));
        return new AccessResult(view(actor, find(id).orElseThrow()), username, p.temporaryPassword(), prov.name(),
                "Senha temporária mostrada só agora: entregue pessoalmente. No primeiro acesso o provedor obriga a trocar.");
    }

    /** Liga o cadastro a uma conta que já existe na empresa (ex.: criada antes deste cadastro). */
    @Transactional
    public EmployeeView linkAccount(Actor actor, UUID id, LinkCommand cmd) {
        Row cur = requireRow(actor, id, "edit_draft");
        if (cur.membershipId() != null) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Este funcionário já tem conta de acesso.");
        }
        UUID user = cmd == null ? null : cmd.user_id();
        var m = user == null ? null : identity.findMembership(actor.organizationId(), user).orElse(null);
        if (m == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Conta inexistente nesta empresa.",
                    List.of(new FieldError("user_id", "Escolha uma conta da empresa")));
        }
        if (jdbc.sql("SELECT count(*) FROM employees WHERE membership_id = :m").param("m", m.membershipId())
                .query(Long.class).single() > 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Conta já ligada a outro funcionário.",
                    List.of(new FieldError("user_id", "Conta já ligada a outro funcionário")));
        }
        jdbc.sql("UPDATE employees SET membership_id = :m, version = version + 1, updated_at = now(), updated_by = :by WHERE id = :id")
                .param("m", m.membershipId()).param("by", actor.userId()).param("id", id).update();
        audit.record(new AuditEntry(actor.organizationId(), cur.branchId(), actor.userId(), "USER", actor.clientId(),
                "employees.account_linked", "employee", id.toString(), "SUCCESS", null, Map.of("user_id", user.toString()), "api"));
        return view(actor, find(id).orElseThrow());
    }

    /** Contas da empresa ainda sem cadastro de funcionário (para "ligar conta existente"). */
    @Transactional(readOnly = true)
    public List<Account> unlinkedAccounts(Actor actor) {
        authz.require(actor, "users", "edit_draft");
        return jdbc.sql("""
                        SELECT m.user_id, u.display_name, CASE WHEN m.status = 'ACTIVE' AND u.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'DISABLED' END AS status,
                               m.role_template, m.sector
                          FROM memberships m JOIN app_users u ON u.id = m.user_id
                         WHERE m.organization_id = :o AND NOT EXISTS (SELECT 1 FROM employees e WHERE e.membership_id = m.id)
                         ORDER BY u.display_name
                        """).param("o", actor.organizationId()).query(Account.class).list();
    }
}
