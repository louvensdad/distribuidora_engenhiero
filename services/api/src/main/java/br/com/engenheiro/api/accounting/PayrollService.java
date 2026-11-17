package br.com.engenheiro.api.accounting;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.finance.Journal.Line;
import br.com.engenheiro.api.finance.PayableService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folha por rubricas (CONT-03; cerebro/02 "Folha"). Sem cálculo trabalhista inventado: os valores vêm do contador
 * (lançados ou importados de arquivo do sistema dele, validado linha a linha). Etapas: preparar → enviar → aprovar
 * (outra pessoa) → pagar os salários líquidos. Aprovar reconhece a despesa da folha uma vez (salários + benefícios +
 * encargos) e cria três contas a pagar: salários líquidos, benefícios, encargos e retenções; adiantamento já pago é
 * compensado (sem dupla dedução). Valor individual é `employee_salary`: sem essa permissão a resposta e a exportação
 * só trazem totais por rubrica — nunca a linha da pessoa.
 */
@Service
public class PayrollService {
    static final Map<String, String> TYPE_ALIASES = Map.of("SALARIO", "EARNING", "ADICIONAL", "EARNING", "PROVENTO", "EARNING",
            "BENEFICIO", "BENEFIT", "DESCONTO", "DEDUCTION", "RETENCAO", "DEDUCTION", "ADIANTAMENTO", "ADVANCE",
            "ENCARGO", "EMPLOYER_CHARGE");
    static final List<String> KINDS = List.of("EARNING", "BENEFIT", "DEDUCTION", "ADVANCE", "EMPLOYER_CHARGE");

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final Journal journal;
    private final PayableService payables;

    public PayrollService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, Journal journal,
                          PayableService payables) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.journal = journal;
        this.payables = payables;
    }

    public record ItemInput(UUID membership_id, String rubric, String kind, String amount) {
    }

    public record CreateCommand(String competence, String description, LocalDate pay_date, List<ItemInput> items) {
    }

    public record NoteCommand(String note) {
    }

    public record PayCommand(UUID fin_account_id, LocalDate paid_on, String note) {
    }

    public record RubricTotal(String kind, String rubric, String amount, int people) {
    }

    public record PersonLine(UUID membership_id, String person, String earnings, String benefits, String deductions,
                             String advances, String employer_charges, String net, List<RubricTotal> rubrics) {
    }

    public record Totals(String earnings, String benefits, String deductions, String advances, String employer_charges,
                         String net_pay, String company_cost, int people) {
    }

    public record RunView(UUID id, String competence, String description, LocalDate pay_date, String state, String source,
                          Totals totals, List<RubricTotal> rubrics, List<PersonLine> people, List<String> masked_fields,
                          UUID net_payable_id, String net_payable_state, String created_by_name, OffsetDateTime created_at,
                          String submitted_by_name, String decided_by_name, OffsetDateTime decided_at, String decided_note,
                          long version) {
    }

    record Run(UUID id, UUID organizationId, UUID branchId, LocalDate competence, String description, LocalDate payDate,
               String state, String source, UUID netPayableId, String netPayableState, UUID benefitsPayableId,
               UUID chargesPayableId, UUID createdBy, String createdByName, OffsetDateTime createdAt, UUID submittedBy,
               String submittedByName, String decidedByName, OffsetDateTime decidedAt, String decidedNote, long version) {
    }

    record Item(UUID membershipId, String person, String rubric, String kind, BigDecimal amount) {
    }

    static final String SELECT = """
            SELECT r.id, r.organization_id, r.branch_id, r.competence, r.description, r.pay_date, r.state, r.source,
                   r.net_payable_id, np.state AS net_payable_state, r.benefits_payable_id, r.charges_payable_id, r.created_by,
                   c.display_name AS created_by_name, r.created_at, r.submitted_by, s.display_name AS submitted_by_name,
                   d.display_name AS decided_by_name, r.decided_at, r.decided_note, r.version
              FROM payroll_runs r JOIN app_users c ON c.id = r.created_by LEFT JOIN app_users s ON s.id = r.submitted_by
              LEFT JOIN app_users d ON d.id = r.decided_by LEFT JOIN payables np ON np.id = r.net_payable_id
            """;

    // ---------------- preparar ----------------
    @Transactional
    public RunView create(Actor actor, CreateCommand cmd) {
        authz.require(actor, "payroll", "create");
        List<FieldError> errors = new ArrayList<>();
        Head h = head(cmd == null ? null : cmd.competence(), cmd == null ? null : cmd.description(),
                cmd == null ? null : cmd.pay_date(), errors);
        List<ItemInput> items = cmd == null || cmd.items() == null ? List.of() : cmd.items();
        List<Item> valid = new ArrayList<>();
        Map<UUID, String> members = members(actor.organizationId());
        for (int i = 0; i < items.size(); i++) {
            ItemInput it = items.get(i);
            String f = "items[" + i + "]";
            String person = it.membership_id() == null ? null : members.get(it.membership_id());
            if (person == null) {
                errors.add(new FieldError(f + ".membership_id", "Funcionário não encontrado"));
            }
            String rubric = Text.trim(it.rubric());
            if (rubric == null || rubric.length() > 60) {
                errors.add(new FieldError(f + ".rubric", "Informe a rubrica"));
            }
            if (it.kind() == null || !KINDS.contains(it.kind())) {
                errors.add(new FieldError(f + ".kind", "Tipo de rubrica inválido"));
            }
            BigDecimal amount = Decimals.parse(it.amount(), Decimals.MONEY_SCALE, false, f + ".amount", errors);
            if (person != null && rubric != null && amount != null && KINDS.contains(it.kind())) {
                valid.add(new Item(it.membership_id(), person, rubric, it.kind(), amount));
            }
        }
        return persist(actor, h, valid, "MANUAL", null, errors);
    }

    /**
     * Importação do arquivo do contador (CSV `funcionario;rubrica;tipo;valor`, separado por ponto e vírgula, com
     * cabeçalho). Funcionário pelo nome cadastrado; tipo SALARIO/ADICIONAL/BENEFICIO/DESCONTO/ADIANTAMENTO/ENCARGO;
     * valor pt-BR. Qualquer linha inválida recusa o arquivo inteiro (nada é gravado pela metade).
     */
    @Transactional
    public RunView importFile(Actor actor, String competence, String description, LocalDate payDate, byte[] bytes) {
        authz.require(actor, "payroll", "create");
        List<FieldError> errors = new ArrayList<>();
        Head h = head(competence, description, payDate, errors);
        if (bytes == null || bytes.length == 0 || bytes.length > 512 * 1024) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Envie o arquivo da folha (CSV até 512 KB).",
                    List.of(new FieldError("file", "Arquivo obrigatório")));
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        String[] lines = text.split("\\r?\\n");
        Map<String, UUID> byName = new HashMap<>();
        members(actor.organizationId()).forEach((id, name) -> byName.put(name.trim().toLowerCase(Locale.ROOT), id));
        List<Item> valid = new ArrayList<>();
        if (lines.length < 2 || !lines[0].trim().toLowerCase(Locale.ROOT).replace(" ", "").equals("funcionario;rubrica;tipo;valor")) {
            errors.add(new FieldError("file", "Cabeçalho esperado: funcionario;rubrica;tipo;valor"));
        } else {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String f = "linha " + (i + 1);
                String[] c = line.split(";", -1);
                if (c.length != 4) {
                    errors.add(new FieldError(f, "Use 4 colunas separadas por ponto e vírgula"));
                    continue;
                }
                UUID m = byName.get(c[0].trim().toLowerCase(Locale.ROOT));
                if (m == null) {
                    errors.add(new FieldError(f, "Funcionário \"" + c[0].trim() + "\" não cadastrado"));
                }
                String kind = TYPE_ALIASES.get(c[2].trim().toUpperCase(Locale.ROOT).replace("Á", "A").replace("Ç", "C")
                        .replace("Ã", "A"));
                if (kind == null) {
                    errors.add(new FieldError(f, "Tipo \"" + c[2].trim() + "\" inválido"));
                }
                String rubric = Text.trim(c[1]);
                if (rubric == null || rubric.length() > 60) {
                    errors.add(new FieldError(f, "Rubrica vazia ou longa demais"));
                }
                String raw = c[3].trim();
                String dec = raw.contains(",") ? raw.replace(".", "").replace(",", ".") : raw;
                List<FieldError> amountErrors = new ArrayList<>();
                BigDecimal amount = Decimals.parse(dec, Decimals.MONEY_SCALE, false, f, amountErrors);
                amountErrors.forEach(e -> errors.add(new FieldError(f, "Valor inválido: " + e.message())));
                if (m != null && kind != null && rubric != null && amount != null) {
                    valid.add(new Item(m, c[0].trim(), rubric, kind, amount));
                }
            }
        }
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return persist(actor, h, valid, "IMPORT", sha, errors);
    }

    record Head(LocalDate competence, String description, LocalDate payDate) {
    }

    private Head head(String competence, String description, LocalDate payDate, List<FieldError> errors) {
        LocalDate comp = null;
        try {
            comp = YearMonth.parse(competence == null ? "" : competence).atDay(1);
        } catch (DateTimeParseException e) {
            errors.add(new FieldError("competence", "Competência no formato AAAA-MM"));
        }
        String desc = Text.trim(description);
        if (desc == null || desc.length() > 120) {
            errors.add(new FieldError("description", "Descreva a folha (ex.: Folha mensal)"));
        }
        if (payDate == null) {
            errors.add(new FieldError("pay_date", "Informe a data de pagamento"));
        }
        return new Head(comp, desc, payDate);
    }

    private RunView persist(Actor actor, Head h, List<Item> items, String source, String sha, List<FieldError> errors) {
        if (items.isEmpty() && errors.isEmpty()) {
            errors.add(new FieldError("items", "A folha precisa de pelo menos uma rubrica"));
        }
        Map<UUID, BigDecimal> net = new HashMap<>();
        for (Item it : items) {
            BigDecimal sign = switch (it.kind()) {
                case "EARNING" -> BigDecimal.ONE;
                case "DEDUCTION", "ADVANCE" -> BigDecimal.ONE.negate();
                default -> BigDecimal.ZERO;
            };
            net.merge(it.membershipId(), it.amount().multiply(sign), BigDecimal::add);
        }
        net.forEach((m, v) -> {
            if (v.signum() < 0) {
                errors.add(new FieldError("items", "Descontos maiores que os proventos de um funcionário (líquido negativo)"));
            }
        });
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A folha não foi gravada: corrija as linhas indicadas.", errors);
        }
        journal.requireOpen(actor.organizationId(), h.competence());
        UUID id = Ids.newId();
        try {
            jdbc.sql("""
                            INSERT INTO payroll_runs (id, organization_id, branch_id, competence, description, pay_date, source,
                                                      source_sha256, created_by)
                            VALUES (:id, :o, :b, :c, :d, :p, :s, :sha, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                    .param("c", h.competence()).param("d", h.description()).param("p", h.payDate()).param("s", source)
                    .param("sha", sha).param("by", actor.userId()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Já existe folha com essa competência e descrição.");
        }
        int n = 1;
        for (Item it : items) {
            jdbc.sql("""
                            INSERT INTO payroll_items (id, run_id, membership_id, rubric, kind, amount, line_no)
                            VALUES (:id, :r, :m, :ru, :k, :a, :n)
                            """).param("id", Ids.newId()).param("r", id).param("m", it.membershipId()).param("ru", it.rubric())
                    .param("k", it.kind()).param("a", it.amount()).param("n", n++).update();
        }
        // Auditoria sem valores individuais (folha nunca vai para log): só quantidade de linhas e pessoas.
        audit.record(entry(actor, "payroll.create", id, Map.of("source", source, "lines", items.size(), "people", net.size(),
                "competence", h.competence().toString())));
        return view(actor, find(id).orElseThrow());
    }

    // ---------------- enviar, aprovar, devolver, pagar ----------------
    @Transactional
    public RunView submit(Actor actor, UUID id, long expectedVersion) {
        Run r = lock(actor, id, "request");
        if (!List.of("DRAFT", "RETURNED").contains(r.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Folha já enviada (" + r.state() + ").");
        }
        conflict(jdbc.sql("""
                        UPDATE payroll_runs SET state = 'SUBMITTED', submitted_by = :by, submitted_at = now(), version = version + 1
                         WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("id", id).param("v", expectedVersion).update());
        audit.record(entry(actor, "payroll.submit", id, Map.of()));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional
    public RunView approve(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Run r = lock(actor, id, "approve");
        decidable(actor, r);
        Totals0 t = totals(id);
        String memo = r.description() + " " + r.competence().getMonthValue() + "/" + r.competence().getYear();
        // Despesa uma vez: proventos + benefícios + encargos. Passivos: líquido, benefícios, encargos+retenções; adiantamento compensado.
        journal.post(actor, r.branchId(), "payroll_run", r.id(), "PAYROLL_RECOGNIZED", memo, null, r.competence(), List.of(
                Line.debit("DESPESAS_FUNCIONARIOS", t.earnings().add(t.benefits()).add(t.charges())),
                Line.credit("FORNECEDORES", t.net().add(t.benefits()).add(t.charges()).add(t.deductions())),
                Line.credit("ADIANTAMENTOS_FUNCIONARIOS", t.advances())));
        UUID netP = t.net().signum() > 0 ? payables.createForSource(actor, r.branchId(), "payroll_net", r.id(), null,
                "Salários líquidos — " + memo, r.competence(), t.net(), r.payDate()) : null;
        UUID benP = t.benefits().signum() > 0 ? payables.createForSource(actor, r.branchId(), "payroll_benefits", r.id(), null,
                "Benefícios — " + memo, r.competence(), t.benefits(), r.payDate()) : null;
        BigDecimal chargesAndWithholdings = t.charges().add(t.deductions());
        UUID chP = chargesAndWithholdings.signum() > 0 ? payables.createForSource(actor, r.branchId(), "payroll_charges", r.id(),
                null, "Encargos e retenções — " + memo, r.competence(), chargesAndWithholdings, null) : null;
        conflict(jdbc.sql("""
                        UPDATE payroll_runs SET state = 'APPROVED', decided_by = :by, decided_at = now(), decided_note = :n,
                               net_payable_id = :np, benefits_payable_id = :bp, charges_payable_id = :cp, version = version + 1
                         WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("n", Text.trim(cmd == null ? null : cmd.note())).param("np", netP)
                .param("bp", benP).param("cp", chP).param("id", id).param("v", expectedVersion).update());
        audit.record(entry(actor, "payroll.approve", id, Map.of("people", t.people())));
        return view(actor, find(id).orElseThrow());
    }

    @Transactional
    public RunView returnToPreparer(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Run r = lock(actor, id, "approve");
        String note = Text.trim(cmd == null ? null : cmd.note());
        if (note == null || note.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("note", "Obrigatório")));
        }
        decidable(actor, r);
        conflict(jdbc.sql("""
                        UPDATE payroll_runs SET state = 'RETURNED', decided_by = :by, decided_at = now(), decided_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("n", note).param("id", id).param("v", expectedVersion).update());
        audit.record(entry(actor, "payroll.return", id, Map.of("reason", note)));
        return view(actor, find(id).orElseThrow());
    }

    /** Paga os salários líquidos (uma baixa do passivo; nenhuma despesa nova). Benefícios/encargos: guias e contas a pagar. */
    @Transactional
    public RunView pay(Actor actor, UUID id, PayCommand cmd) {
        Run r = lock(actor, id, "confirm");
        if (!r.state().equals("APPROVED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, r.state().equals("PAID") ? "Folha já paga." : "Só folha aprovada é paga.");
        }
        if (r.netPayableId() != null) {
            BigDecimal bal = payables.balanceOf(r.netPayableId());
            if (bal.signum() > 0) {
                payables.payFor(actor, r.netPayableId(), bal, cmd == null ? null : cmd.fin_account_id(),
                        cmd == null ? null : cmd.paid_on(), "Pagamento de salários — " + r.description());
            }
        }
        jdbc.sql("UPDATE payroll_runs SET state = 'PAID', version = version + 1 WHERE id = :id").param("id", id).update();
        audit.record(entry(actor, "payroll.pay", id, Map.of()));
        return view(actor, find(id).orElseThrow());
    }

    private void decidable(Actor actor, Run r) {
        if (!r.state().equals("SUBMITTED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só folha enviada para aprovação pode ser decidida.");
        }
        if (!actor.admin() && (actor.userId().equals(r.createdBy()) || actor.userId().equals(r.submittedBy()))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Quem preparou a folha não a aprova.");
        }
    }

    // ---------------- consultas e exportação ----------------
    @Transactional(readOnly = true)
    public List<RunView> list(Actor actor) {
        authz.require(actor, "payroll", "view");
        return jdbc.sql(SELECT + " WHERE r.organization_id = :o ORDER BY r.competence DESC, r.created_at DESC LIMIT 60")
                .param("o", actor.organizationId()).query(Run.class).list().stream().map(r -> view(actor, r)).toList();
    }

    @Transactional(readOnly = true)
    public RunView get(Actor actor, UUID id) {
        authz.require(actor, "payroll", "view");
        return view(actor, find(id).filter(r -> r.organizationId().equals(actor.organizationId()))
                .orElseThrow(ApiException::notFound));
    }

    /** CSV para o contador/arquivo: por pessoa só com `employee_salary`; sem ela, totais por rubrica. */
    @Transactional
    public String export(Actor actor, UUID id) {
        authz.require(actor, "payroll", "export");
        RunView v = get(actor, id);
        StringBuilder sb = new StringBuilder();
        if (v.people() != null) {
            sb.append("funcionario;rubrica;tipo;valor\n");
            for (PersonLine p : v.people()) {
                for (RubricTotal t : p.rubrics()) {
                    sb.append(csv(p.person())).append(';').append(csv(t.rubric())).append(';').append(t.kind()).append(';')
                            .append(t.amount().replace('.', ',')).append('\n');
                }
            }
        } else {
            sb.append("rubrica;tipo;valor;pessoas\n");
            for (RubricTotal t : v.rubrics()) {
                sb.append(csv(t.rubric())).append(';').append(t.kind()).append(';').append(t.amount().replace('.', ','))
                        .append(';').append(t.people()).append('\n');
            }
        }
        audit.record(entry(actor, "payroll.export", id, Map.of("individual", v.people() != null)));
        return sb.toString();
    }

    record Totals0(BigDecimal earnings, BigDecimal benefits, BigDecimal deductions, BigDecimal advances, BigDecimal charges,
                   BigDecimal net, int people) {
    }

    Totals0 totals(UUID runId) {
        Map<String, BigDecimal> k = new HashMap<>();
        for (Item it : items(runId)) {
            k.merge(it.kind(), it.amount(), BigDecimal::add);
        }
        BigDecimal e = k.getOrDefault("EARNING", BigDecimal.ZERO);
        BigDecimal d = k.getOrDefault("DEDUCTION", BigDecimal.ZERO);
        BigDecimal a = k.getOrDefault("ADVANCE", BigDecimal.ZERO);
        int people = jdbc.sql("SELECT count(DISTINCT membership_id) FROM payroll_items WHERE run_id = :r").param("r", runId)
                .query(Integer.class).single();
        return new Totals0(e, k.getOrDefault("BENEFIT", BigDecimal.ZERO), d, a, k.getOrDefault("EMPLOYER_CHARGE", BigDecimal.ZERO),
                e.subtract(d).subtract(a), people);
    }

    private List<Item> items(UUID runId) {
        return jdbc.sql("""
                        SELECT i.membership_id, u.display_name AS person, i.rubric, i.kind, i.amount FROM payroll_items i
                          JOIN memberships m ON m.id = i.membership_id JOIN app_users u ON u.id = m.user_id
                         WHERE i.run_id = :r ORDER BY u.display_name, i.line_no
                        """).param("r", runId).query(Item.class).list();
    }

    private RunView view(Actor actor, Run r) {
        Totals0 t = totals(r.id());
        List<Item> items = items(r.id());
        Map<String, BigDecimal> byRubric = new LinkedHashMap<>();
        Map<String, java.util.Set<UUID>> peopleByRubric = new HashMap<>();
        for (Item it : items) {
            String k = it.kind() + "|" + it.rubric();
            byRubric.merge(k, it.amount(), BigDecimal::add);
            peopleByRubric.computeIfAbsent(k, x -> new java.util.HashSet<>()).add(it.membershipId());
        }
        List<RubricTotal> rubrics = byRubric.entrySet().stream().map(e -> new RubricTotal(e.getKey().split("\\|")[0],
                e.getKey().split("\\|", 2)[1], Decimals.moneyStr(e.getValue()), peopleByRubric.get(e.getKey()).size())).toList();
        boolean individual = authz.canSeeField(actor, FieldClass.EMPLOYEE_SALARY);
        List<PersonLine> people = null;
        if (individual) {
            people = new ArrayList<>();
            Map<UUID, List<Item>> byPerson = new LinkedHashMap<>();
            items.forEach(it -> byPerson.computeIfAbsent(it.membershipId(), x -> new ArrayList<>()).add(it));
            for (Map.Entry<UUID, List<Item>> e : byPerson.entrySet()) {
                Map<String, BigDecimal> k = new HashMap<>();
                e.getValue().forEach(it -> k.merge(it.kind(), it.amount(), BigDecimal::add));
                BigDecimal earn = k.getOrDefault("EARNING", BigDecimal.ZERO);
                BigDecimal ded = k.getOrDefault("DEDUCTION", BigDecimal.ZERO);
                BigDecimal adv = k.getOrDefault("ADVANCE", BigDecimal.ZERO);
                people.add(new PersonLine(e.getKey(), e.getValue().get(0).person(), Decimals.moneyStr(earn),
                        Decimals.moneyStr(k.getOrDefault("BENEFIT", BigDecimal.ZERO)), Decimals.moneyStr(ded), Decimals.moneyStr(adv),
                        Decimals.moneyStr(k.getOrDefault("EMPLOYER_CHARGE", BigDecimal.ZERO)),
                        Decimals.moneyStr(earn.subtract(ded).subtract(adv)),
                        e.getValue().stream().map(it -> new RubricTotal(it.kind(), it.rubric(), Decimals.moneyStr(it.amount()), 1)).toList()));
            }
        }
        Totals totals = new Totals(Decimals.moneyStr(t.earnings()), Decimals.moneyStr(t.benefits()), Decimals.moneyStr(t.deductions()),
                Decimals.moneyStr(t.advances()), Decimals.moneyStr(t.charges()), Decimals.moneyStr(t.net()),
                Decimals.moneyStr(t.earnings().add(t.benefits()).add(t.charges())), t.people());
        return new RunView(r.id(), YearMonth.from(r.competence()).toString(), r.description(), r.payDate(), r.state(), r.source(),
                totals, rubrics, people, individual ? List.of() : List.of("people"), r.netPayableId(), r.netPayableState(),
                r.createdByName(), r.createdAt(), r.submittedByName(), r.decidedByName(), r.decidedAt(), r.decidedNote(), r.version());
    }

    private Map<UUID, String> members(UUID org) {
        Map<UUID, String> m = new LinkedHashMap<>();
        record M(UUID id, String name) {
        }
        jdbc.sql("""
                        SELECT m.id, u.display_name AS name FROM memberships m JOIN app_users u ON u.id = m.user_id
                         WHERE m.organization_id = :o AND m.status = 'ACTIVE'
                        """).param("o", org).query(M.class).list().forEach(x -> m.put(x.id(), x.name()));
        return m;
    }

    private Run lock(Actor actor, UUID id, String action) {
        authz.require(actor, "payroll", action);
        jdbc.sql("SELECT id FROM payroll_runs WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return find(id).filter(r -> r.organizationId().equals(actor.organizationId())).orElseThrow(ApiException::notFound);
    }

    private Optional<Run> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(Run.class).optional();
    }

    private static void conflict(int n) {
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A folha foi alterada por outra pessoa. Recarregue.");
        }
    }

    private static String csv(String s) {
        return s.replace(";", ",").replace("\n", " ");
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                "payroll_run", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
