package br.com.engenheiro.api.finance.openfinance;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Open Finance: estado do agregador, conexões por conta bancária, sincronização e conferência do extrato (ADR-021). */
@RestController
@RequestMapping("/api/v1/finance/open-finance")
public class OpenFinanceController {
    private final OpenFinanceService service;

    public OpenFinanceController(OpenFinanceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<OpenFinanceService.Status> status(Actor actor) {
        return ok(service.status(actor));
    }

    @PostMapping("/connections")
    public ResponseEntity<OpenFinanceService.ConnectionView> connect(Actor actor, @RequestBody OpenFinanceService.ConnectCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.connect(actor, body));
    }

    @PostMapping("/connections/{id}/sync")
    public ResponseEntity<OpenFinanceService.SyncResult> sync(Actor actor, @PathVariable UUID id) {
        return ok(service.sync(actor, id));
    }

    @PostMapping("/connections/{id}/revoke")
    public ResponseEntity<OpenFinanceService.ConnectionView> revoke(Actor actor, @PathVariable UUID id,
            @RequestBody(required = false) OpenFinanceService.ReasonCommand body) {
        return ok(service.revoke(actor, id, body));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Map<String, List<OpenFinanceService.TxView>>> transactions(Actor actor,
            @RequestParam(required = false) String state) {
        return ok(Map.of("items", service.transactions(actor, state)));
    }

    @PostMapping("/transactions/{id}/ignore")
    public ResponseEntity<OpenFinanceService.TxView> ignore(Actor actor, @PathVariable UUID id,
            @RequestBody OpenFinanceService.ReasonCommand body) {
        return ok(service.ignore(actor, id, body));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
