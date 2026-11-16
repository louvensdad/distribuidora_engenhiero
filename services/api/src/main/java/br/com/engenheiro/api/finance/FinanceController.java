package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** F06 — contas, transferências, despesas, contas a pagar/receber, resultado, detalhamento, conciliação e períodos. */
@RestController
public class FinanceController {
    private static final String IDEM = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private final FinAccountService accounts;
    private final ExpenseService expenses;
    private final PayableService payables;
    private final ReceivableService receivables;
    private final FinanceReportService reports;
    private final PeriodService periods;

    public FinanceController(FinAccountService accounts, ExpenseService expenses, PayableService payables,
                             ReceivableService receivables, FinanceReportService reports, PeriodService periods) {
        this.accounts = accounts;
        this.expenses = expenses;
        this.payables = payables;
        this.receivables = receivables;
        this.reports = reports;
        this.periods = periods;
    }

    // ---- contas e transferências ----
    @GetMapping("/api/v1/finance/accounts")
    public ResponseEntity<Map<String, List<FinAccountService.AccountView>>> accounts(Actor actor) {
        return ok(Map.of("items", accounts.list(actor)));
    }

    @PostMapping("/api/v1/finance/accounts")
    public ResponseEntity<FinAccountService.AccountView> createAccount(Actor actor,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody FinAccountService.CreateCommand body) {
        var v = accounts.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/finance/accounts/" + v.id())).cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/api/v1/finance/accounts/{id}/statement")
    public ResponseEntity<FinAccountService.Statement> statement(Actor actor, @PathVariable UUID id,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return ok(accounts.statement(actor, id, from, to));
    }

    @GetMapping("/api/v1/finance/transfers")
    public ResponseEntity<Map<String, List<FinAccountService.TransferView>>> transfers(Actor actor) {
        return ok(Map.of("items", accounts.transfers(actor)));
    }

    @PostMapping("/api/v1/finance/transfers")
    public ResponseEntity<FinAccountService.TransferView> transfer(Actor actor,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody FinAccountService.TransferCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(accounts.transfer(actor, key, body));
    }

    @PostMapping("/api/v1/finance/transfers/{id}/reverse")
    public ResponseEntity<FinAccountService.TransferView> reverseTransfer(Actor actor, @PathVariable UUID id,
            @RequestBody FinAccountService.ReasonCommand body) {
        return ok(accounts.reverseTransfer(actor, id, body));
    }

    // ---- despesas ----
    @GetMapping("/api/v1/expenses")
    public ResponseEntity<ExpenseService.Page> expenses(Actor actor, @RequestParam(required = false) String state,
            @RequestParam(required = false) String category, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor) {
        return ok(expenses.list(actor, state, category, from, to, limit, cursor));
    }

    @PostMapping("/api/v1/expenses")
    public ResponseEntity<ExpenseService.ExpenseView> createExpense(Actor actor,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody ExpenseService.CreateCommand body) {
        var v = expenses.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/expenses/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/api/v1/expenses/{id}")
    public ResponseEntity<ExpenseService.ExpenseView> expense(Actor actor, @PathVariable UUID id) {
        var v = expenses.get(actor, id);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/api/v1/expenses/{id}/approve")
    public ResponseEntity<ExpenseService.ExpenseView> approveExpense(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) ExpenseService.NoteCommand body) {
        return ok(expenses.approve(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/expenses/{id}/reject")
    public ResponseEntity<ExpenseService.ExpenseView> rejectExpense(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody ExpenseService.NoteCommand body) {
        return ok(expenses.reject(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/expenses/{id}/reverse")
    public ResponseEntity<ExpenseService.ExpenseView> reverseExpense(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody ExpenseService.NoteCommand body) {
        return ok(expenses.reverse(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping(value = "/api/v1/expenses/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<DocumentView> attachExpense(Actor actor, @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(expenses.attach(actor, id, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/api/v1/expenses/{id}/attachments")
    public ResponseEntity<Map<String, List<DocumentView>>> expenseAttachments(Actor actor, @PathVariable UUID id) {
        return ok(Map.of("items", expenses.attachments(actor, id)));
    }

    // ---- contas a pagar ----
    @GetMapping("/api/v1/payables")
    public ResponseEntity<PayableService.Page> payables(Actor actor, @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "25") int limit, @RequestParam(required = false) String cursor) {
        return ok(payables.list(actor, state, limit, cursor));
    }

    @GetMapping("/api/v1/payables/{id}")
    public ResponseEntity<PayableService.Detail> payable(Actor actor, @PathVariable UUID id) {
        return ok(payables.get(actor, id));
    }

    @PostMapping("/api/v1/payables/{id}/payments")
    public ResponseEntity<PayableService.Detail> pay(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody PayableService.PayCommand body) {
        return ok(payables.pay(actor, id, key, body));
    }

    @PostMapping("/api/v1/payable-settlements/{id}/reverse")
    public ResponseEntity<PayableService.Detail> reversePayment(Actor actor, @PathVariable UUID id,
            @RequestBody PayableService.ReasonCommand body) {
        return ok(payables.reverseSettlement(actor, id, body));
    }

    // ---- contas a receber ----
    @GetMapping("/api/v1/receivables")
    public ResponseEntity<Map<String, List<ReceivableService.ReceivableView>>> receivables(Actor actor,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String state) {
        return ok(Map.of("items", receivables.list(actor, kind, state)));
    }

    @GetMapping("/api/v1/receivables/{id}")
    public ResponseEntity<ReceivableService.Detail> receivable(Actor actor, @PathVariable UUID id) {
        return ok(receivables.get(actor, id));
    }

    @PostMapping("/api/v1/receivables/{id}/settlements")
    public ResponseEntity<ReceivableService.Detail> settle(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody ReceivableService.SettleCommand body) {
        return ok(receivables.settle(actor, id, key, body));
    }

    @PostMapping("/api/v1/receivable-settlements/{id}/reverse")
    public ResponseEntity<ReceivableService.Detail> reverseSettlement(Actor actor, @PathVariable UUID id,
            @RequestBody ReceivableService.ReasonCommand body) {
        return ok(receivables.reverseSettlement(actor, id, body));
    }

    // ---- resultado, detalhamento e conciliação ----
    @GetMapping("/api/v1/reports/summary")
    public ResponseEntity<FinanceReportService.Summary> summary(Actor actor, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ok(reports.summary(actor, from, to));
    }

    @GetMapping("/api/v1/reports/categories")
    public ResponseEntity<FinanceReportService.Categories> categories(Actor actor, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ok(reports.categories(actor, from, to));
    }

    @GetMapping("/api/v1/finance/ledger-lines")
    public ResponseEntity<FinanceReportService.Lines> lines(Actor actor, @RequestParam String accounts,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String basis, @RequestParam(required = false) UUID fin_account_id) {
        return ok(reports.lines(actor, Arrays.stream(accounts.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                from, to, basis, fin_account_id));
    }

    @GetMapping("/api/v1/finance/reconciliation")
    public ResponseEntity<FinanceReportService.Reconciliation> reconciliation(Actor actor) {
        return ok(reports.reconciliation(actor));
    }

    // ---- períodos ----
    @GetMapping("/api/v1/periods")
    public ResponseEntity<Map<String, List<PeriodService.PeriodView>>> periods(Actor actor) {
        return ok(Map.of("items", periods.list(actor)));
    }

    @PostMapping("/api/v1/periods/{id}/close")
    public ResponseEntity<PeriodService.PeriodView> close(Actor actor, @PathVariable String id,
            @RequestBody(required = false) PeriodService.ReasonCommand body) {
        return ok(periods.close(actor, id, body));
    }

    @PostMapping("/api/v1/periods/{id}/reopen-requests")
    public ResponseEntity<PeriodService.PeriodView> requestReopen(Actor actor, @PathVariable String id,
            @RequestBody PeriodService.ReasonCommand body) {
        return ok(periods.requestReopen(actor, id, body));
    }

    @PostMapping("/api/v1/periods/{id}/reopen")
    public ResponseEntity<PeriodService.PeriodView> reopen(Actor actor, @PathVariable String id,
            @RequestBody(required = false) PeriodService.ReasonCommand body) {
        return ok(periods.approveReopen(actor, id, body));
    }

    @PostMapping("/api/v1/periods/{id}/reopen-rejections")
    public ResponseEntity<PeriodService.PeriodView> rejectReopen(Actor actor, @PathVariable String id,
            @RequestBody PeriodService.ReasonCommand body) {
        return ok(periods.rejectReopen(actor, id, body));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
