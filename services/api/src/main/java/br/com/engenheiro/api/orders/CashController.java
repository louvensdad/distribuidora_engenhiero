package br.com.engenheiro.api.orders;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.orders.CashService.SessionView;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cash-sessions")
public class CashController {
    private static final String IDEM = "Idempotency-Key";
    private final CashService cash;

    public CashController(CashService cash) {
        this.cash = cash;
    }

    @PostMapping
    public ResponseEntity<SessionView> open(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                            @RequestBody CashService.OpenCommand body) {
        SessionView v = cash.open(actor, key, body);
        return ResponseEntity.status(201).eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> current(Actor actor) {
        Map<String, Object> body = new HashMap<>();
        body.put("session", cash.current(actor).orElse(null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping
    public ResponseEntity<Map<String, List<SessionView>>> list(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", cash.list(actor)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<SessionView> close(Actor actor, @PathVariable UUID id,
                                             @RequestHeader(value = IDEM, required = false) String key,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @RequestBody CashService.CloseCommand body) {
        return ok(cash.close(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<SessionView> review(Actor actor, @PathVariable UUID id,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @RequestBody(required = false) CashService.ReviewCommand body) {
        return ok(cash.review(actor, id, IfMatch.version(ifMatch), body));
    }

    private static ResponseEntity<SessionView> ok(SessionView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }
}
