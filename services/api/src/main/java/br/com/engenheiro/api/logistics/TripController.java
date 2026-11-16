package br.com.engenheiro.api.logistics;

import br.com.engenheiro.api.documents.DocumentService.DocumentView;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.logistics.TripService.TripView;
import br.com.engenheiro.api.orders.PaymentService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Viagens: despacho (E04), minha viagem (E05), entrega (E06/E07), ocorrência (E09), retorno (E10), custos (LOG-08). */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {
    private static final String IDEM = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    @GetMapping
    public ResponseEntity<TripService.Page> list(Actor actor, @RequestParam(required = false) String state,
                                                 @RequestParam(defaultValue = "25") int limit,
                                                 @RequestParam(required = false) String cursor) {
        return noStore(trips.list(actor, state, limit, cursor));
    }

    @PostMapping
    public ResponseEntity<TripView> create(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                           @RequestBody(required = false) TripService.CreateCommand body) {
        TripView v = trips.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/trips/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/eligible-drivers")
    public ResponseEntity<Map<String, List<TripService.DriverOption>>> drivers(Actor actor) {
        return noStore(Map.of("items", trips.eligibleDrivers(actor)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TripView> get(Actor actor, @PathVariable UUID id) {
        return ok(trips.get(actor, id));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<Map<String, List<TripService.HistoryItem>>> history(Actor actor, @PathVariable UUID id) {
        return noStore(Map.of("items", trips.history(actor, id)));
    }

    @GetMapping("/{id}/costs")
    public ResponseEntity<TripService.Costs> costs(Actor actor, @PathVariable UUID id) {
        return noStore(trips.costs(actor, id));
    }

    @PostMapping("/{id}/assignments")
    public ResponseEntity<TripView> assign(Actor actor, @PathVariable UUID id,
                                           @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                           @RequestBody TripService.AssignCommand body) {
        return ok(trips.assign(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/depart")
    public ResponseEntity<TripView> depart(Actor actor, @PathVariable UUID id,
                                           @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                           @RequestBody TripService.DepartCommand body) {
        return ok(trips.depart(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/deliveries")
    public ResponseEntity<TripView> deliver(Actor actor, @PathVariable UUID id,
                                            @RequestHeader(value = IDEM, required = false) String key,
                                            @RequestBody TripService.DeliverCommand body) {
        return ok(trips.deliver(actor, id, key, body));
    }

    @PostMapping("/{id}/stops/{stopId}/payments")
    public ResponseEntity<PaymentService.RegisterResult> collect(Actor actor, @PathVariable UUID id,
                                                                 @PathVariable UUID stopId,
                                                                 @RequestHeader(value = IDEM, required = false) String key,
                                                                 @RequestBody PaymentService.RegisterCommand body) {
        return noStore(trips.collect(actor, id, stopId, key, body));
    }

    @PostMapping("/{id}/incidents")
    public ResponseEntity<TripView> incident(Actor actor, @PathVariable UUID id,
                                             @RequestHeader(value = IDEM, required = false) String key,
                                             @RequestBody TripService.IncidentCommand body) {
        return ok(trips.incident(actor, id, key, body));
    }

    @PostMapping("/{id}/return")
    public ResponseEntity<TripView> returnTrip(Actor actor, @PathVariable UUID id,
                                               @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                               @RequestBody TripService.ReturnCommand body) {
        return ok(trips.returnTrip(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/reconcile")
    public ResponseEntity<TripView> reconcile(Actor actor, @PathVariable UUID id,
                                              @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                              @RequestBody(required = false) TripService.NoteCommand body) {
        return ok(trips.reconcile(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<TripView> cancel(Actor actor, @PathVariable UUID id,
                                           @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
                                           @RequestBody TripService.NoteCommand body) {
        return ok(trips.cancel(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping(value = "/{id}/stops/{stopId}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<DocumentView> attach(Actor actor, @PathVariable UUID id, @PathVariable UUID stopId,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(trips.attach(actor, id, stopId, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/{id}/stops/{stopId}/attachments")
    public ResponseEntity<Map<String, List<DocumentView>>> attachments(Actor actor, @PathVariable UUID id,
                                                                       @PathVariable UUID stopId) {
        return noStore(Map.of("items", trips.attachments(actor, id, stopId)));
    }

    private static ResponseEntity<TripView> ok(TripView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
