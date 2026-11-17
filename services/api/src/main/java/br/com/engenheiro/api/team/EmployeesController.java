package br.com.engenheiro.api.team;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cadastro de funcionários e cargos (ADR-027, pedido do responsável). */
@RestController
public class EmployeesController {
    private final EmployeeService employees;
    private final JobPositionService positions;

    public EmployeesController(EmployeeService employees, JobPositionService positions) {
        this.employees = employees;
        this.positions = positions;
    }

    private static <T> ResponseEntity<T> ok(T body, long version) {
        return ResponseEntity.ok().eTag("\"" + version + "\"").cacheControl(CacheControl.noStore()).body(body);
    }

    // ---------- cargos ----------
    @GetMapping("/api/v1/job-positions")
    public ResponseEntity<Map<String, List<JobPositionService.PositionView>>> positions(Actor actor,
            @RequestParam(defaultValue = "false") boolean include_archived) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", positions.list(actor, include_archived)));
    }

    @PostMapping("/api/v1/job-positions")
    public ResponseEntity<JobPositionService.PositionView> createPosition(Actor actor, @RequestBody JobPositionService.Command body) {
        var v = positions.create(actor, body);
        return ResponseEntity.created(URI.create("/api/v1/job-positions/" + v.id())).eTag("\"" + v.version() + "\"").body(v);
    }

    @PutMapping("/api/v1/job-positions/{id}")
    public ResponseEntity<JobPositionService.PositionView> updatePosition(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody JobPositionService.Command body) {
        var v = positions.update(actor, id, IfMatch.version(ifMatch), body);
        return ok(v, v.version());
    }

    // ---------- funcionários ----------
    @GetMapping("/api/v1/employees")
    public ResponseEntity<EmployeeService.Page> list(Actor actor, @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) UUID job_position_id,
                                                     @RequestParam(defaultValue = "25") int limit,
                                                     @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(employees.list(actor, q, status, job_position_id, limit, cursor));
    }

    @PostMapping("/api/v1/employees")
    public ResponseEntity<EmployeeService.EmployeeView> create(Actor actor, @RequestBody EmployeeService.Command body) {
        var v = employees.create(actor, body);
        return ResponseEntity.created(URI.create("/api/v1/employees/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/api/v1/employees/{id}")
    public ResponseEntity<EmployeeService.EmployeeView> get(Actor actor, @PathVariable UUID id) {
        var v = employees.get(actor, id);
        return ok(v, v.version());
    }

    @PutMapping("/api/v1/employees/{id}")
    public ResponseEntity<EmployeeService.EmployeeView> update(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestBody EmployeeService.Command body) {
        var v = employees.update(actor, id, IfMatch.version(ifMatch), body);
        return ok(v, v.version());
    }

    @PostMapping("/api/v1/employees/{id}/termination")
    public ResponseEntity<EmployeeService.EmployeeView> terminate(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) EmployeeService.TerminationCommand body) {
        var v = employees.terminate(actor, id, IfMatch.version(ifMatch), body);
        return ok(v, v.version());
    }

    @PostMapping("/api/v1/employees/{id}/access")
    public ResponseEntity<EmployeeService.AccessResult> grantAccess(Actor actor, @PathVariable UUID id,
            @RequestBody(required = false) EmployeeService.AccessCommand body) {
        // A senha temporária só existe nesta resposta: nunca em cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(employees.grantAccess(actor, id, body));
    }

    @PostMapping("/api/v1/employees/{id}/account-link")
    public ResponseEntity<EmployeeService.EmployeeView> link(Actor actor, @PathVariable UUID id,
            @RequestBody(required = false) EmployeeService.LinkCommand body) {
        var v = employees.linkAccount(actor, id, body);
        return ok(v, v.version());
    }

    @GetMapping("/api/v1/employees/unlinked-accounts")
    public ResponseEntity<Map<String, Object>> unlinked(Actor actor) {
        var p = employees.provisioner();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", employees.unlinkedAccounts(actor),
                "account_creation", p == null ? "NOT_CONFIGURED" : "CONFIGURED"));
    }
}
