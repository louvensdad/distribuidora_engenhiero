package br.com.engenheiro.api.catalogparties.suppliers;

import br.com.engenheiro.api.catalogparties.suppliers.SupplierService.CreateCommand;
import br.com.engenheiro.api.catalogparties.suppliers.SupplierService.Page;
import br.com.engenheiro.api.catalogparties.suppliers.SupplierService.SupplierView;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import java.net.URI;
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

@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {
    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    public record CreateSupplierRequest(String name, String contact, String personal_document) {
    }

    @PostMapping
    public ResponseEntity<SupplierView> create(Actor actor, @RequestBody CreateSupplierRequest body) {
        SupplierView v = service.create(actor, new CreateCommand(body.name(), body.contact(), body.personal_document()));
        return ResponseEntity.created(URI.create("/api/v1/suppliers/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupplierView> get(Actor actor, @PathVariable UUID id) {
        SupplierView v = service.get(actor, id);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping
    public ResponseEntity<Page> list(Actor actor, @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "25") int limit,
                                     @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor, q, limit, cursor));
    }
}
