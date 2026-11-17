package br.com.engenheiro.api.accounting;

import br.com.engenheiro.api.audit.RecordHistory;
import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** F07 — guias, folha e central de documentos (contador prepara; patrão aprova e paga; tudo com histórico). */
@RestController
public class AccountingController {
    private static final String IDEM = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private final TaxObligationService taxes;
    private final PayrollService payroll;
    private final AccountingDocumentService docs;
    private final RecordHistory history;

    public AccountingController(TaxObligationService taxes, PayrollService payroll, AccountingDocumentService docs,
                                RecordHistory history) {
        this.taxes = taxes;
        this.payroll = payroll;
        this.docs = docs;
        this.history = history;
    }

    // ---- guias ----
    @GetMapping("/api/v1/tax-obligations")
    public ResponseEntity<Map<String, List<TaxObligationService.TaxView>>> taxes(Actor actor, @RequestParam(required = false) String state) {
        return ok(Map.of("items", taxes.list(actor, state)));
    }

    @PostMapping("/api/v1/tax-obligations")
    public ResponseEntity<TaxObligationService.TaxView> createTax(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                                                  @RequestBody TaxObligationService.DraftCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(taxes.create(actor, key, body));
    }

    @GetMapping("/api/v1/tax-obligations/{id}")
    public ResponseEntity<TaxObligationService.TaxView> tax(Actor actor, @PathVariable UUID id) {
        return ok(taxes.get(actor, id));
    }

    @PostMapping("/api/v1/tax-obligations/{id}/draft")
    public ResponseEntity<TaxObligationService.TaxView> updateTax(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody TaxObligationService.DraftCommand body) {
        return ok(taxes.update(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/tax-obligations/{id}/submit")
    public ResponseEntity<TaxObligationService.TaxView> submitTax(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        return ok(taxes.submit(actor, id, IfMatch.version(ifMatch)));
    }

    @PostMapping("/api/v1/tax-obligations/{id}/approval")
    public ResponseEntity<TaxObligationService.TaxView> approveTax(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) TaxObligationService.NoteCommand body) {
        return ok(taxes.approve(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/tax-obligations/{id}/return")
    public ResponseEntity<TaxObligationService.TaxView> returnTax(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody TaxObligationService.NoteCommand body) {
        return ok(taxes.returnToPreparer(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/tax-obligations/{id}/payments")
    public ResponseEntity<TaxObligationService.TaxView> payTax(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody TaxObligationService.PayCommand body) {
        return ok(taxes.pay(actor, id, key, body));
    }

    @PostMapping("/api/v1/tax-obligations/{id}/cancel")
    public ResponseEntity<TaxObligationService.TaxView> cancelTax(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody TaxObligationService.NoteCommand body) {
        return ok(taxes.cancel(actor, id, IfMatch.version(ifMatch), body));
    }

    /** C04: histórico da decisão da guia. Mesma autorização do detalhe (escopo do registro). */
    @GetMapping("/api/v1/tax-obligations/{id}/history")
    public ResponseEntity<Map<String, List<RecordHistory.Item>>> taxHistory(Actor actor, @PathVariable UUID id) {
        taxes.requireVisible(actor, id);
        return ok(Map.of("items", history.of(actor.organizationId(), "tax_obligation", id)));
    }

    @PostMapping(value = "/api/v1/tax-obligations/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<DocumentView> attachTax(Actor actor, @PathVariable UUID id, @RequestParam("file") MultipartFile file)
            throws IOException {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(taxes.attach(actor, id, file.getOriginalFilename(), file.getBytes()));
    }

    // ---- folha ----
    @GetMapping("/api/v1/payroll-runs")
    public ResponseEntity<Map<String, List<PayrollService.RunView>>> runs(Actor actor) {
        return ok(Map.of("items", payroll.list(actor)));
    }

    @PostMapping("/api/v1/payroll-runs")
    public ResponseEntity<PayrollService.RunView> createRun(Actor actor, @RequestBody PayrollService.CreateCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(payroll.create(actor, body));
    }

    @PostMapping(value = "/api/v1/payroll-runs/imports", consumes = "multipart/form-data")
    public ResponseEntity<PayrollService.RunView> importRun(Actor actor, @RequestParam String competence,
            @RequestParam String description, @RequestParam LocalDate pay_date, @RequestParam("file") MultipartFile file)
            throws IOException {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(payroll.importFile(actor, competence, description, pay_date, file.getBytes()));
    }

    @GetMapping("/api/v1/payroll-runs/{id}")
    public ResponseEntity<PayrollService.RunView> run(Actor actor, @PathVariable UUID id) {
        return ok(payroll.get(actor, id));
    }

    @PostMapping("/api/v1/payroll-runs/{id}/submit")
    public ResponseEntity<PayrollService.RunView> submitRun(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        return ok(payroll.submit(actor, id, IfMatch.version(ifMatch)));
    }

    @PostMapping("/api/v1/payroll-runs/{id}/approval")
    public ResponseEntity<PayrollService.RunView> approveRun(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) PayrollService.NoteCommand body) {
        return ok(payroll.approve(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/payroll-runs/{id}/return")
    public ResponseEntity<PayrollService.RunView> returnRun(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody PayrollService.NoteCommand body) {
        return ok(payroll.returnToPreparer(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/payroll-runs/{id}/cancel")
    public ResponseEntity<PayrollService.RunView> cancelRun(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody PayrollService.NoteCommand body) {
        return ok(payroll.cancel(actor, id, IfMatch.version(ifMatch), body));
    }

    /** C05: histórico da folha (quem preparou, enviou, devolveu/aprovou e por qual app). Mesma autorização do detalhe. */
    @GetMapping("/api/v1/payroll-runs/{id}/history")
    public ResponseEntity<Map<String, List<RecordHistory.Item>>> runHistory(Actor actor, @PathVariable UUID id) {
        payroll.get(actor, id);
        return ok(Map.of("items", history.of(actor.organizationId(), "payroll_run", id)));
    }

    @PostMapping("/api/v1/payroll-runs/{id}/payments")
    public ResponseEntity<PayrollService.RunView> payRun(Actor actor, @PathVariable UUID id,
            @RequestBody PayrollService.PayCommand body) {
        return ok(payroll.pay(actor, id, body));
    }

    @GetMapping("/api/v1/payroll-runs/{id}/export")
    public ResponseEntity<byte[]> exportRun(Actor actor, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"folha.csv\"")
                .cacheControl(CacheControl.noStore()).body(payroll.export(actor, id).getBytes(StandardCharsets.UTF_8));
    }

    // ---- documentos ----
    @GetMapping("/api/v1/documents")
    public ResponseEntity<Map<String, List<AccountingDocumentService.DocView>>> documents(Actor actor,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String competence) {
        return ok(Map.of("items", docs.list(actor, kind, competence)));
    }

    @GetMapping("/api/v1/documents/{id}")
    public ResponseEntity<AccountingDocumentService.DocView> document(Actor actor, @PathVariable UUID id) {
        return ok(docs.get(actor, id));
    }

    @PostMapping(value = "/api/v1/documents/uploads", consumes = "multipart/form-data")
    public ResponseEntity<AccountingDocumentService.DocView> upload(Actor actor, @RequestParam String kind,
            @RequestParam String title, @RequestParam(required = false) String competence,
            @RequestParam(required = false) UUID request_id, @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(docs.upload(actor, kind, title, competence, request_id, file.getOriginalFilename(), file.getBytes()));
    }

    @PostMapping(value = "/api/v1/documents/{id}/versions", consumes = "multipart/form-data")
    public ResponseEntity<AccountingDocumentService.DocView> version(Actor actor, @PathVariable UUID id,
            @RequestParam String reason, @RequestParam("file") MultipartFile file) throws IOException {
        return ok(docs.newVersion(actor, id, reason, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/api/v1/document-requests")
    public ResponseEntity<Map<String, List<AccountingDocumentService.RequestView>>> requests(Actor actor,
            @RequestParam(required = false) String state) {
        return ok(Map.of("items", docs.requests(actor, state)));
    }

    @PostMapping("/api/v1/document-requests")
    public ResponseEntity<AccountingDocumentService.RequestView> request(Actor actor,
            @RequestBody AccountingDocumentService.RequestCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(docs.requestDocument(actor, body));
    }

    @PostMapping("/api/v1/document-requests/{id}/cancel")
    public ResponseEntity<AccountingDocumentService.RequestView> cancelRequest(Actor actor, @PathVariable UUID id) {
        return ok(docs.cancelRequest(actor, id));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
