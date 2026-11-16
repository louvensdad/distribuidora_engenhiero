package br.com.engenheiro.api.catalogparties.products;

import br.com.engenheiro.api.catalogparties.products.ProductService.ConversionInput;
import br.com.engenheiro.api.catalogparties.products.ProductService.CreateCommand;
import br.com.engenheiro.api.catalogparties.products.ProductService.Page;
import br.com.engenheiro.api.catalogparties.products.ProductService.ProductView;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.net.URI;
import java.util.List;
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
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    public record CreateProductRequest(String sku, String name, String base_unit, Boolean base_unit_confirmed,
                                       List<ConversionInput> conversions) {
    }

    @PostMapping
    public ResponseEntity<ProductView> create(Actor actor, @RequestBody CreateProductRequest body) {
        ProductView v = service.create(actor, new CreateCommand(body.sku(), body.name(), body.base_unit(),
                body.base_unit_confirmed(), body.conversions()));
        return ResponseEntity.created(URI.create("/api/v1/products/" + v.id())).eTag("\"" + v.version() + "\"")
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> get(Actor actor, @PathVariable UUID id) {
        return ok(service.get(actor, id));
    }

    @GetMapping
    public ResponseEntity<Page> list(Actor actor, @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "25") int limit,
                                     @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(actor, q, limit, cursor));
    }

    @PostMapping("/{id}/conversions")
    public ResponseEntity<ProductView> addConversion(Actor actor, @PathVariable UUID id,
                                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     @RequestBody ConversionInput body) {
        return ok(service.addConversion(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/prices")
    public ResponseEntity<ProductView> addPrice(Actor actor, @PathVariable UUID id,
                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                @RequestBody ProductService.PriceInput body) {
        return ok(service.addPrice(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/{id}/unit-confirmation")
    public ResponseEntity<ProductView> confirmUnit(Actor actor, @PathVariable UUID id,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ok(service.confirmUnit(actor, id, IfMatch.version(ifMatch)));
    }

    private static ResponseEntity<ProductView> ok(ProductView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }
}
