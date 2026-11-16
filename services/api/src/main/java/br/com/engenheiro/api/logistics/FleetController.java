package br.com.engenheiro.api.logistics;

import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.logistics.VehicleExpenseService.ExpenseView;
import br.com.engenheiro.api.logistics.VehicleService.VehicleView;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.io.IOException;
import java.net.URI;
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

/** Frota: veículos, odômetro, abastecimentos, manutenção e demais gastos (W07/E08/E11). */
@RestController
public class FleetController {
    private static final String IDEM = "Idempotency-Key";
    private final VehicleService vehicles;
    private final VehicleExpenseService expenses;

    public FleetController(VehicleService vehicles, VehicleExpenseService expenses) {
        this.vehicles = vehicles;
        this.expenses = expenses;
    }

    @GetMapping("/api/v1/vehicles")
    public ResponseEntity<VehicleService.Page> list(Actor actor, @RequestParam(required = false) String status) {
        return noStore(vehicles.list(actor, status));
    }

    @PostMapping("/api/v1/vehicles")
    public ResponseEntity<VehicleView> create(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                              @RequestBody VehicleService.CreateCommand body) {
        VehicleView v = vehicles.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/vehicles/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/api/v1/vehicles/{id}")
    public ResponseEntity<VehicleView> get(Actor actor, @PathVariable UUID id) {
        return tagged(vehicles.get(actor, id));
    }

    @PostMapping("/api/v1/vehicles/{id}/status")
    public ResponseEntity<VehicleView> status(Actor actor, @PathVariable UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody VehicleService.StatusCommand body) {
        return tagged(vehicles.changeStatus(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/api/v1/vehicles/{id}/odometer-corrections")
    public ResponseEntity<VehicleView> correct(Actor actor, @PathVariable UUID id,
                                               @RequestBody VehicleService.OdometerCorrection body) {
        return tagged(vehicles.correctOdometer(actor, id, body));
    }

    @GetMapping("/api/v1/vehicles/{id}/odometer")
    public ResponseEntity<Map<String, List<VehicleService.OdometerView>>> odometer(Actor actor, @PathVariable UUID id) {
        return noStore(Map.of("items", vehicles.odometer(actor, id)));
    }

    @GetMapping("/api/v1/vehicles/{id}/history")
    public ResponseEntity<Map<String, List<VehicleService.HistoryItem>>> history(Actor actor, @PathVariable UUID id) {
        return noStore(Map.of("items", vehicles.history(actor, id)));
    }

    @PostMapping("/api/v1/vehicles/{id}/fuel")
    public ResponseEntity<ExpenseView> fuel(Actor actor, @PathVariable UUID id,
                                            @RequestHeader(value = IDEM, required = false) String key,
                                            @RequestBody VehicleExpenseService.FuelCommand body) {
        return created(expenses.registerFuel(actor, id, key, body));
    }

    @PostMapping("/api/v1/vehicles/{id}/maintenance")
    public ResponseEntity<ExpenseView> maintenance(Actor actor, @PathVariable UUID id,
                                                   @RequestHeader(value = IDEM, required = false) String key,
                                                   @RequestBody VehicleExpenseService.MaintenanceCommand body) {
        return created(expenses.registerMaintenance(actor, id, key, body));
    }

    @GetMapping("/api/v1/vehicle-expenses")
    public ResponseEntity<Map<String, List<ExpenseView>>> expenses(Actor actor,
                                                                   @RequestParam(required = false) UUID vehicle_id,
                                                                   @RequestParam(required = false) UUID trip_id,
                                                                   @RequestParam(required = false) String state) {
        return noStore(Map.of("items", expenses.list(actor, vehicle_id, trip_id, state)));
    }

    @GetMapping("/api/v1/vehicle-expenses/{id}")
    public ResponseEntity<ExpenseView> expense(Actor actor, @PathVariable UUID id) {
        ExpenseView v = expenses.get(actor, id);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/api/v1/vehicle-expenses/{id}/approve")
    public ResponseEntity<ExpenseView> approve(Actor actor, @PathVariable UUID id,
                                               @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody(required = false) VehicleExpenseService.DecisionCommand body) {
        ExpenseView v = expenses.approve(actor, id, key, IfMatch.version(ifMatch), body);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/api/v1/vehicle-expenses/{id}/reject")
    public ResponseEntity<ExpenseView> reject(Actor actor, @PathVariable UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody VehicleExpenseService.DecisionCommand body) {
        ExpenseView v = expenses.reject(actor, id, IfMatch.version(ifMatch), body);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping(value = "/api/v1/vehicle-expenses/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<DocumentView> attach(Actor actor, @PathVariable UUID id,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(expenses.attach(actor, id, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/api/v1/vehicle-expenses/{id}/attachments")
    public ResponseEntity<Map<String, List<DocumentView>>> attachments(Actor actor, @PathVariable UUID id) {
        return noStore(Map.of("items", expenses.attachments(actor, id)));
    }

    private static ResponseEntity<ExpenseView> created(ExpenseView v) {
        return ResponseEntity.status(201).eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    private static ResponseEntity<VehicleView> tagged(VehicleView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
