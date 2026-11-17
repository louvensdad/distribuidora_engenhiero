package br.com.engenheiro.api.orders;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.orders.OrderService.OrderView;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private static final String IDEM = "Idempotency-Key";
    private final OrderService orders;
    private final PaymentService payments;

    public OrderController(OrderService orders, PaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<OrderView> create(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                            @RequestBody OrderService.CreateCommand body) {
        OrderView v = orders.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping
    public ResponseEntity<OrderService.Page> list(Actor actor, @RequestParam(required = false) String type,
                                                  @RequestParam(required = false) String state,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "25") int limit,
                                                  @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.list(actor, type, state, q, limit, cursor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderView> get(Actor actor, @PathVariable UUID id) {
        return ok(orders.get(actor, id));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<Map<String, List<OrderService.HistoryItem>>> history(Actor actor, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", orders.history(actor, id)));
    }

    @PostMapping("/{id}/ready")
    public ResponseEntity<OrderView> ready(Actor actor, @PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ok(orders.markReady(actor, id, IfMatch.version(ifMatch)));
    }

    @PostMapping("/{id}/fulfillments")
    public ResponseEntity<OrderView> fulfill(Actor actor, @PathVariable UUID id,
                                             @RequestHeader(value = IDEM, required = false) String key,
                                             @RequestBody(required = false) OrderService.FulfillCommand body) {
        return ok(orders.fulfill(actor, id, key, body));
    }

    @PostMapping("/{id}/cancellations")
    public ResponseEntity<OrderView> cancel(Actor actor, @PathVariable UUID id,
                                            @RequestHeader(value = IDEM, required = false) String key,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @RequestBody OrderService.ReasonCommand body) {
        return ok(orders.cancel(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/returns")
    public ResponseEntity<OrderView> returns(Actor actor, @PathVariable UUID id,
                                             @RequestHeader(value = IDEM, required = false) String key,
                                             @RequestBody OrderService.ReturnCommand body) {
        return ok(orders.returnItems(actor, id, key, body));
    }

    @PostMapping("/{id}/payments")
    public ResponseEntity<PaymentService.RegisterResult> pay(Actor actor, @PathVariable UUID id,
                                                             @RequestHeader(value = IDEM, required = false) String key,
                                                             @RequestBody PaymentService.RegisterCommand body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.register(actor, id, key, body));
    }

    @GetMapping("/{id}/payments")
    public ResponseEntity<Map<String, List<PaymentService.PaymentView>>> payments(Actor actor, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", payments.listForOrder(actor, id)));
    }

    private static ResponseEntity<OrderView> ok(OrderView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }
}
