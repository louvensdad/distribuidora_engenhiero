package br.com.engenheiro.api.accounting;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.documents.DocumentService;
import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.finance.Journal;
import br.com.engenheiro.api.finance.Journal.Line;
import br.com.engenheiro.api.finance.PayableService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.application.ScopeSql;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.shared.idempotency.IdempotencyService;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.values.Decimals;
import br.com.engenheiro.api.shared.values.Text;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guias e obrigações (CONT-01, CONT-02; cerebro/02 "Contador, guia e pagamento"). Etapas independentes:
 * preparar (rascunho, `taxes.create`) → enviar com o documento da guia (`taxes.request`) → aprovar ou devolver com
 * motivo (`taxes.approve`, outra pessoa) → pagar com conta, data e comprovante (`taxes.confirm`). Aprovar reconhece a
 * despesa uma vez (competência) e cria a conta a pagar; a guia aprovada continua NÃO PAGA até a confirmação. Guia de
 * encargos/retenções ligada a uma folha paga o passivo já reconhecido pela folha (sem nova despesa).
 */
@Service
public class TaxObligationService {
    static final List<String> KINDS = List.of("DAS", "ICMS", "ISS", "INSS", "FGTS", "IRRF", "OUTRA");
    static final ScopeSql.Columns COLUMNS = new ScopeSql.Columns("t.organization_id", "t.branch_id", null, "t.created_by", null);

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final IdempotencyService idem;
    private final Journal journal;
    private final PayableService payables;
    private final DocumentService documents;

    public TaxObligationService(JdbcClient jdbc, AuthorizationService authz, AuditService audit, IdempotencyService idem,
                                Journal journal, PayableService payables, DocumentService documents) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.idem = idem;
        this.journal = journal;
        this.payables = payables;
        this.documents = documents;
    }

    public record DraftCommand(String kind, String description, String competence, LocalDate due_date, String amount,
                               String notes, UUID payroll_run_id) {
    }

    public record NoteCommand(String note) {
    }

    public record PayCommand(UUID fin_account_id, LocalDate paid_on, UUID proof_document_id, String note) {
    }

    public record TaxView(UUID id, String kind, String description, String competence, LocalDate due_date, String amount,
                          String notes, UUID payroll_run_id, String state, UUID payable_id, String payable_state,
                          String created_by_name, OffsetDateTime created_at, String submitted_by_name,
                          OffsetDateTime submitted_at, String decided_by_name, OffsetDateTime decided_at, String decided_note,
                          List<DocumentView> attachments, long version) {
    }

    record Row(UUID id, UUID organizationId, UUID branchId, String kind, String description, LocalDate competence,
               LocalDate dueDate, BigDecimal amount, String notes, UUID payrollRunId, String state, UUID payableId,
               String payableState, UUID createdBy, String createdByName, OffsetDateTime createdAt, UUID submittedBy,
               String submittedByName, OffsetDateTime submittedAt, String decidedByName, OffsetDateTime decidedAt,
               String decidedNote, long version) {
    }

    static final String SELECT = """
            SELECT t.id, t.organization_id, t.branch_id, t.kind, t.description, t.competence, t.due_date, t.amount, t.notes,
                   t.payroll_run_id, t.state, t.payable_id, p.state AS payable_state, t.created_by, c.display_name AS created_by_name,
                   t.created_at, t.submitted_by, s.display_name AS submitted_by_name, t.submitted_at,
                   d.display_name AS decided_by_name, t.decided_at, t.decided_note, t.version
              FROM tax_obligations t JOIN app_users c ON c.id = t.created_by LEFT JOIN app_users s ON s.id = t.submitted_by
              LEFT JOIN app_users d ON d.id = t.decided_by LEFT JOIN payables p ON p.id = t.payable_id
            """;

    // ---------------- preparar ----------------
    @Transactional
    public TaxView create(Actor actor, String idemKey, DraftCommand cmd) {
        authz.require(actor, "taxes", "create");
        return idem.execute(actor, "taxes.create", idemKey, cmd, TaxView.class, () -> {
            Valid v = validate(actor, cmd);
            UUID id = Ids.newId();
            jdbc.sql("""
                            INSERT INTO tax_obligations (id, organization_id, branch_id, kind, description, competence, due_date,
                                                         amount, notes, payroll_run_id, created_by)
                            VALUES (:id, :o, :b, :k, :d, :c, :due, :a, :n, :pr, :by)
                            """).param("id", id).param("o", actor.organizationId()).param("b", actor.branchId())
                    .param("k", v.kind()).param("d", v.description()).param("c", v.competence()).param("due", v.due())
                    .param("a", v.amount()).param("n", v.notes()).param("pr", v.payrollRun()).param("by", actor.userId()).update();
            audit.record(entry(actor, "taxes.create", id, Map.of("kind", v.kind(), "competence", v.competence().toString(),
                    "amount", v.amount().toPlainString())));
            return view(find(id).orElseThrow());
        });
    }

    /** Ajustar rascunho ou guia devolvida (nunca depois de enviada/aprovada). */
    @Transactional
    public TaxView update(Actor actor, UUID id, long expectedVersion, DraftCommand cmd) {
        Row t = lockVisible(actor, id, "create");
        if (!t.state().equals("DRAFT") && !t.state().equals("RETURNED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só rascunho ou guia devolvida pode ser alterada.");
        }
        Valid v = validate(actor, cmd);
        int n = jdbc.sql("""
                        UPDATE tax_obligations SET kind = :k, description = :d, competence = :c, due_date = :due, amount = :a,
                               notes = :n, payroll_run_id = :pr, version = version + 1 WHERE id = :id AND version = :v
                        """).param("k", v.kind()).param("d", v.description()).param("c", v.competence()).param("due", v.due())
                .param("a", v.amount()).param("n", v.notes()).param("pr", v.payrollRun()).param("id", id)
                .param("v", expectedVersion).update();
        conflict(n);
        audit.record(entry(actor, "taxes.update", id, Map.of("amount", v.amount().toPlainString())));
        return view(find(id).orElseThrow());
    }

    record Valid(String kind, String description, LocalDate competence, LocalDate due, BigDecimal amount, String notes,
                 UUID payrollRun) {
    }

    private Valid validate(Actor actor, DraftCommand cmd) {
        List<FieldError> errors = new ArrayList<>();
        if (cmd == null || cmd.kind() == null || !KINDS.contains(cmd.kind())) {
            errors.add(new FieldError("kind", "Escolha o tipo de guia"));
        }
        String desc = Text.trim(cmd == null ? null : cmd.description());
        if (desc == null || desc.length() > 200) {
            errors.add(new FieldError("description", "Descreva a guia (até 200 caracteres)"));
        }
        LocalDate comp = null;
        try {
            comp = YearMonth.parse(cmd == null ? "" : cmd.competence()).atDay(1);
        } catch (DateTimeParseException | NullPointerException e) {
            errors.add(new FieldError("competence", "Competência no formato AAAA-MM"));
        }
        if (cmd == null || cmd.due_date() == null) {
            errors.add(new FieldError("due_date", "Informe o vencimento"));
        } else if (comp != null && cmd.due_date().isBefore(comp)) {
            errors.add(new FieldError("due_date", "Vencimento antes da competência"));
        }
        BigDecimal amount = Decimals.parse(cmd == null ? null : cmd.amount(), Decimals.MONEY_SCALE, false, "amount", errors);
        String notes = Text.trim(cmd == null ? null : cmd.notes());
        if (notes != null && notes.length() > 500) {
            errors.add(new FieldError("notes", "Até 500 caracteres"));
        }
        if (cmd != null && cmd.payroll_run_id() != null && jdbc.sql(
                        "SELECT count(*) FROM payroll_runs WHERE id = :id AND organization_id = :o")
                .param("id", cmd.payroll_run_id()).param("o", actor.organizationId()).query(Integer.class).single() == 0) {
            errors.add(new FieldError("payroll_run_id", "Folha não encontrada"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
        }
        return new Valid(cmd.kind(), desc, comp, cmd.due_date(), amount, notes, cmd.payroll_run_id());
    }

    // ---------------- enviar para aprovação ----------------
    @Transactional
    public TaxView submit(Actor actor, UUID id, long expectedVersion) {
        Row t = lockVisible(actor, id, "request");
        if (!t.state().equals("DRAFT") && !t.state().equals("RETURNED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Guia já enviada (" + t.state() + ").");
        }
        long docs = documents.list("tax_obligation", id).stream().filter(DocumentService.DocumentView::available).count();
        if (docs == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Anexe o arquivo da guia antes de enviar.",
                    List.of(new FieldError("attachments", "Arquivo da guia obrigatório")));
        }
        int n = jdbc.sql("""
                        UPDATE tax_obligations SET state = 'SUBMITTED', submitted_by = :by, submitted_at = now(),
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("id", id).param("v", expectedVersion).update();
        conflict(n);
        audit.record(entry(actor, "taxes.submit", id, Map.of("attachments", docs)));
        return view(find(id).orElseThrow());
    }

    // ---------------- aprovar / devolver ----------------
    @Transactional
    public TaxView approve(Actor actor, UUID id, String idemKey, long expectedVersion, NoteCommand cmd) {
        Row t = lockVisible(actor, id, "approve");
        return idem.execute(actor, "taxes.approve", idemKey, Map.of("id", id.toString(), "version", expectedVersion),
                TaxView.class, () -> {
                    requireDecidable(actor, t);
                    UUID payable;
                    if (t.payrollRunId() != null) {
                        // Encargos/retenções da folha: o passivo e a despesa já nasceram na aprovação da folha.
                        record PR(String state, UUID chargesPayableId) {
                        }
                        PR pr = jdbc.sql("SELECT state, charges_payable_id FROM payroll_runs WHERE id = :id FOR UPDATE")
                                .param("id", t.payrollRunId()).query(PR.class).single();
                        if (!List.of("APPROVED", "PAID").contains(pr.state()) || pr.chargesPayableId() == null) {
                            throw new ApiException(ErrorCode.INVALID_TRANSITION, "A folha ligada ainda não foi aprovada.");
                        }
                        BigDecimal committed = jdbc.sql("""
                                        SELECT coalesce(sum(amount), 0) FROM tax_obligations
                                         WHERE payroll_run_id = :r AND state = 'APPROVED' AND id <> :id
                                        """).param("r", t.payrollRunId()).param("id", t.id()).query(BigDecimal.class).single();
                        if (committed.add(t.amount()).compareTo(payables.balanceOf(pr.chargesPayableId())) > 0) {
                            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Valor acima dos encargos/retenções em aberto da folha.",
                                    List.of(new FieldError("amount", "Maior que o saldo de encargos da folha")));
                        }
                        payable = pr.chargesPayableId();
                    } else {
                        String memo = t.kind() + " " + t.competence().getMonthValue() + "/" + t.competence().getYear() + " — " + t.description();
                        journal.post(actor, t.branchId(), "tax_obligation", t.id(), "TAX_RECOGNIZED", memo, null, t.competence(),
                                List.of(Line.debit("DESPESAS_IMPOSTOS", t.amount()), Line.credit("FORNECEDORES", t.amount())));
                        payable = payables.createForSource(actor, t.branchId(), "tax_obligation", t.id(), null, "Guia " + memo,
                                t.competence(), t.amount(), t.dueDate());
                    }
                    int n = jdbc.sql("""
                                    UPDATE tax_obligations SET state = 'APPROVED', payable_id = :p, decided_by = :by, decided_at = now(),
                                           decided_note = :n, version = version + 1 WHERE id = :id AND version = :v
                                    """).param("p", payable).param("by", actor.userId())
                            .param("n", Text.trim(cmd == null ? null : cmd.note())).param("id", t.id()).param("v", expectedVersion)
                            .update();
                    conflict(n);
                    Map<String, Object> ch = new HashMap<>(Map.of("amount", t.amount().toPlainString()));
                    String approvalNote = Text.trim(cmd == null ? null : cmd.note());
                    if (approvalNote != null) {
                        ch.put("note", approvalNote);
                    }
                    audit.record(entry(actor, "taxes.approve", t.id(), ch));
                    return view(find(t.id()).orElseThrow());
                });
    }

    @Transactional
    public TaxView returnToPreparer(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row t = lockVisible(actor, id, "approve");
        String note = Text.trim(cmd == null ? null : cmd.note());
        if (note == null || note.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo da devolução.",
                    List.of(new FieldError("note", "Motivo obrigatório")));
        }
        requireDecidable(actor, t);
        int n = jdbc.sql("""
                        UPDATE tax_obligations SET state = 'RETURNED', decided_by = :by, decided_at = now(), decided_note = :n,
                               version = version + 1 WHERE id = :id AND version = :v
                        """).param("by", actor.userId()).param("n", note).param("id", id).param("v", expectedVersion).update();
        conflict(n);
        audit.record(entry(actor, "taxes.return", id, Map.of("reason", note)));
        return view(find(id).orElseThrow());
    }

    private void requireDecidable(Actor actor, Row t) {
        if (!t.state().equals("SUBMITTED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Só guia enviada para aprovação pode ser decidida.");
        }
        if (!actor.admin() && (actor.userId().equals(t.createdBy()) || actor.userId().equals(t.submittedBy()))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Quem preparou a guia não a aprova.");
        }
    }

    // ---------------- pagar (etapa separada) ----------------
    @Transactional
    public TaxView pay(Actor actor, UUID id, String idemKey, PayCommand cmd) {
        Row t = lockVisible(actor, id, "confirm");
        Map<String, Object> req = new HashMap<>();
        req.put("id", id.toString());
        req.put("body", cmd);
        return idem.execute(actor, "taxes.pay", idemKey, req, TaxView.class, () -> {
            if (!t.state().equals("APPROVED")) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, t.state().equals("PAID") ? "Guia já paga."
                        : "Só guia aprovada pode ser paga.");
            }
            if (cmd != null && cmd.proof_document_id() != null && documents.list("tax_obligation", id).stream()
                    .noneMatch(d -> d.id().equals(cmd.proof_document_id()) && d.available())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Comprovante não pertence a esta guia.",
                        List.of(new FieldError("proof_document_id", "Anexe o comprovante na guia")));
            }
            String note = (cmd != null && cmd.proof_document_id() != null ? "Comprovante anexado. " : "Sem comprovante anexado. ")
                    + (cmd == null || Text.trim(cmd.note()) == null ? "" : Text.trim(cmd.note()));
            payables.payFor(actor, t.payableId(), t.amount(), cmd == null ? null : cmd.fin_account_id(),
                    cmd == null ? null : cmd.paid_on(), "Guia " + t.kind() + " — " + note.trim());
            jdbc.sql("UPDATE tax_obligations SET state = 'PAID', version = version + 1 WHERE id = :id").param("id", id).update();
            audit.record(entry(actor, "taxes.pay", id, Map.of("amount", t.amount().toPlainString(),
                    "proof", cmd != null && cmd.proof_document_id() != null)));
            return view(find(id).orElseThrow());
        });
    }

    @Transactional
    public TaxView cancel(Actor actor, UUID id, long expectedVersion, NoteCommand cmd) {
        Row t = lockVisible(actor, id, "create");
        String note = Text.trim(cmd == null ? null : cmd.note());
        if (note == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.", List.of(new FieldError("note", "Obrigatório")));
        }
        if (!List.of("DRAFT", "RETURNED").contains(t.state())) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Guia enviada/aprovada não é cancelada pelo preparador.");
        }
        int n = jdbc.sql("UPDATE tax_obligations SET state = 'CANCELLED', decided_note = :n, version = version + 1 WHERE id = :id AND version = :v")
                .param("n", note).param("id", id).param("v", expectedVersion).update();
        conflict(n);
        audit.record(entry(actor, "taxes.cancel", id, Map.of("reason", note)));
        return view(find(id).orElseThrow());
    }

    // ---------------- anexos e consultas ----------------
    @Transactional
    public DocumentView attach(Actor actor, UUID id, String fileName, byte[] bytes) {
        Row t = find(id).filter(r -> r.organizationId().equals(actor.organizationId())).orElse(null);
        if (t == null) {
            authz.require(actor, "taxes", "view");
            throw ApiException.notFound();
        }
        Decision create = authz.decide(actor, "taxes", "create");
        Decision confirm = authz.decide(actor, "taxes", "confirm");
        boolean preparer = create.allowed() && List.of("DRAFT", "RETURNED").contains(t.state());
        boolean payer = confirm.allowed() && t.state().equals("APPROVED"); // comprovante de pagamento
        if (!preparer && !payer && !actor.admin()) {
            authz.require(actor, "taxes", preparer ? "create" : "confirm");
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Anexo só no rascunho (guia) ou na guia aprovada (comprovante).");
        }
        DocumentView d = documents.attach(actor, t.branchId(), "tax_obligation", id, fileName, bytes);
        audit.record(entry(actor, "taxes.attach", id, Map.of("document", d.id().toString(), "state", d.state())));
        return d;
    }

    @Transactional(readOnly = true)
    public List<TaxView> list(Actor actor, String state) {
        Decision d = authz.require(actor, "taxes", "view");
        if (state != null && !List.of("DRAFT", "SUBMITTED", "RETURNED", "APPROVED", "PAID", "CANCELLED").contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "state inválido.");
        }
        ScopeSql.Predicate p = ScopeSql.of(actor, d.scopes(), COLUMNS);
        var spec = jdbc.sql(SELECT + " WHERE " + p.sql() + " AND (CAST(:st AS text) IS NULL OR t.state = CAST(:st AS text))"
                + " ORDER BY t.due_date DESC, t.created_at DESC LIMIT 200").param("st", state);
        for (Map.Entry<String, Object> e : p.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Row.class).list().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public TaxView get(Actor actor, UUID id) {
        return view(requireVisible(actor, id, "view"));
    }

    /** Para download de anexo: mesma autorização da guia. */
    @Transactional(readOnly = true)
    public void requireVisible(Actor actor, UUID id) {
        requireVisible(actor, id, "view");
    }

    private Row requireVisible(Actor actor, UUID id, String action) {
        Row t = find(id).filter(r -> r.organizationId().equals(actor.organizationId())).orElse(null);
        if (t == null) {
            authz.require(actor, "taxes", action);
            throw ApiException.notFound();
        }
        authz.requireRecord(actor, "taxes", action, "tax_obligation", id.toString(),
                new RecordRef(t.organizationId(), t.branchId(), null, t.createdBy(), null));
        return t;
    }

    private Row lockVisible(Actor actor, UUID id, String action) {
        jdbc.sql("SELECT id FROM tax_obligations WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
        return requireVisible(actor, id, action);
    }

    private Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(Row.class).optional();
    }

    private static void conflict(int n) {
        if (n == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "A guia foi alterada por outra pessoa. Recarregue.");
        }
    }

    private TaxView view(Row t) {
        return new TaxView(t.id(), t.kind(), t.description(), YearMonth.from(t.competence()).toString(), t.dueDate(),
                Decimals.moneyStr(t.amount()), t.notes(), t.payrollRunId(), t.state(), t.payableId(), t.payableState(),
                t.createdByName(), t.createdAt(), t.submittedByName(), t.submittedAt(), t.decidedByName(), t.decidedAt(),
                t.decidedNote(), documents.list("tax_obligation", t.id()), t.version());
    }

    static AuditEntry entry(Actor actor, String action, UUID id, Map<String, Object> changes) {
        return new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER", actor.clientId(), action,
                "tax_obligation", id.toString(), "SUCCESS", null, new HashMap<>(changes), "api");
    }
}
