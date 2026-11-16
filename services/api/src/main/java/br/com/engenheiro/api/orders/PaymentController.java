package br.com.engenheiro.api.orders;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.orders.PaymentService.NoteCommand;
import br.com.engenheiro.api.orders.PaymentService.PaymentView;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private static final String IDEM = "Idempotency-Key";
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentView> confirm(Actor actor, @PathVariable UUID id,
                                               @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody NoteCommand body) {
        return ok(payments.confirm(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<PaymentView> reject(Actor actor, @PathVariable UUID id,
                                              @RequestHeader(value = IDEM, required = false) String key,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody NoteCommand body) {
        return ok(payments.reject(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/reversals")
    public ResponseEntity<PaymentView> reverse(Actor actor, @PathVariable UUID id,
                                               @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody NoteCommand body) {
        return ok(payments.reverse(actor, id, key, IfMatch.version(ifMatch), body));
    }

    private static ResponseEntity<PaymentView> ok(PaymentView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }
}
