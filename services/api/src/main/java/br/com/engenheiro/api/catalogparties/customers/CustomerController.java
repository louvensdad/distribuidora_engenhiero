package br.com.engenheiro.api.catalogparties.customers;

import br.com.engenheiro.api.catalogparties.customers.CustomerService.CreateCommand;
import br.com.engenheiro.api.catalogparties.customers.CustomerService.CustomerView;
import br.com.engenheiro.api.catalogparties.customers.CustomerService.Page;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import br.com.engenheiro.api.shared.web.IfMatch;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    /** Corpo fechado: organização, unidade e autor vêm da sessão, nunca do cliente. */
    public record CreateCustomerRequest(String name, String contact, String address, String personal_document) {
    }

    @PostMapping
    public ResponseEntity<CustomerView> create(Actor actor, @RequestBody CreateCustomerRequest body) {
        CustomerView v = service.create(actor, new CreateCommand(body.name(), body.contact(), body.address(),
                body.personal_document()));
        return ResponseEntity.created(URI.create("/api/v1/customers/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    public record UpdateCustomerRequest(String name, String contact, String address, String personal_document,
                                        String status) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerView> update(Actor actor, @PathVariable UUID id,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody UpdateCustomerRequest body) {
        CustomerView v = service.update(actor, id, IfMatch.version(ifMatch), new CustomerService.UpdateCommand(
                body.name(), body.contact(), body.address(), body.personal_document(), body.status()));
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerView> get(Actor actor, @PathVariable UUID id) {
        CustomerView v = service.get(actor, id);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping
    public ResponseEntity<Page> list(Actor actor, @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "25") int limit,
                                     @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor, q, limit, cursor));
    }
}
