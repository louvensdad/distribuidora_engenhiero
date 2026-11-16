package br.com.engenheiro.api.finance;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payables")
public class PayableController {
    private final PayableService service;

    public PayableController(PayableService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PayableService.Page> list(Actor actor, @RequestParam(required = false) String state,
                                                    @RequestParam(defaultValue = "25") int limit,
                                                    @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor, state, limit, cursor));
    }
}
