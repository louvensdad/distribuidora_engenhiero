package br.com.engenheiro.api.reports;

import br.com.engenheiro.api.finance.FinanceReportService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** F09: relatório canônico, drilldown, arquivos, ranking de clientes e metas. */
@RestController
public class ReportsController {
    private static final String IF_MATCH = "If-Match";
    private final ReportService reports;
    private final ReportExporter exporter;
    private final GoalService goals;
    private final CustomerRankingService customers;

    public ReportsController(ReportService reports, ReportExporter exporter, GoalService goals, CustomerRankingService customers) {
        this.reports = reports;
        this.exporter = exporter;
        this.goals = goals;
        this.customers = customers;
    }

    @GetMapping("/api/v1/reports/overview")
    public ResponseEntity<ReportService.Report> overview(Actor actor, @RequestParam(required = false) String period,
            @RequestParam(required = false) LocalDate date, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID branch_id) {
        return ok(reports.report(actor, new ReportService.Filter(period, date, from, to, branch_id)));
    }

    @GetMapping("/api/v1/reports/overview/export")
    public ResponseEntity<byte[]> export(Actor actor, @RequestParam String format, @RequestParam(required = false) String period,
            @RequestParam(required = false) LocalDate date, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID branch_id) {
        ReportService.FileOut f = reports.export(actor, new ReportService.Filter(period, date, from, to, branch_id), format, exporter);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.name()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.contentType())).body(f.body());
    }

    @GetMapping("/api/v1/reports/lines")
    public ResponseEntity<FinanceReportService.Lines> lines(Actor actor, @RequestParam String accounts,
            @RequestParam(required = false) String period, @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID branch_id) {
        List<String> accs = Arrays.stream(accounts.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ok(reports.lines(actor, accs, new ReportService.Filter(period, date, from, to, branch_id)));
    }

    @GetMapping("/api/v1/reports/customers")
    public ResponseEntity<CustomerRankingService.Ranking> customers(Actor actor, @RequestParam(required = false) String period,
            @RequestParam(required = false) LocalDate date, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID branch_id,
            @RequestParam(required = false) String sort) {
        return ok(customers.customers(actor, new ReportService.Filter(period, date, from, to, branch_id), sort));
    }

    // ---- metas ----
    @GetMapping("/api/v1/goals")
    public ResponseEntity<Map<String, List<GoalService.GoalView>>> goals(Actor actor, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) UUID branch_id) {
        return ok(Map.of("items", goals.list(actor, from, to, branch_id)));
    }

    @PostMapping("/api/v1/goals")
    public ResponseEntity<GoalService.GoalView> createGoal(Actor actor, @RequestBody GoalService.Command body) {
        GoalService.GoalView v = goals.create(actor, body);
        return ResponseEntity.status(201).eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/api/v1/goals/{id}")
    public ResponseEntity<GoalService.GoalView> goal(Actor actor, @PathVariable UUID id) {
        return tagged(goals.get(actor, id));
    }

    @PutMapping("/api/v1/goals/{id}")
    public ResponseEntity<GoalService.GoalView> updateGoal(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody GoalService.Command body) {
        return tagged(goals.update(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/goals/{id}/activation")
    public ResponseEntity<GoalService.GoalView> activateGoal(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        return tagged(goals.activate(actor, id, IfMatch.version(ifMatch)));
    }

    @PostMapping("/api/v1/goals/{id}/cancellation")
    public ResponseEntity<GoalService.GoalView> cancelGoal(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody GoalService.ReasonCommand body) {
        return tagged(goals.cancel(actor, id, IfMatch.version(ifMatch), body));
    }

    private static ResponseEntity<GoalService.GoalView> tagged(GoalService.GoalView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
