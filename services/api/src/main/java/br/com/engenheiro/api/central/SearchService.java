package br.com.engenheiro.api.central;

import br.com.engenheiro.api.catalogparties.customers.CustomerService;
import br.com.engenheiro.api.catalogparties.products.ProductService;
import br.com.engenheiro.api.catalogparties.suppliers.SupplierService;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.logistics.TripService;
import br.com.engenheiro.api.logistics.VehicleService;
import br.com.engenheiro.api.orders.OrderService;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import br.com.engenheiro.api.shared.web.SearchText;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Busca geral do site (F10, SEC-08). Cada grupo só é consultado se o ator pode ver aquele recurso, e usa a lista do
 * próprio módulo (mesmo escopo de linha). Só procura em campos que o resultado já exibe a quem o vê — número, nome,
 * SKU, placa —, nunca em endereço, contato, documento, salário ou custo; o resultado carrega só título/subtítulo/link.
 * Sem permissão para nada pesquisável → 403.
 */
@Service
public class SearchService {
    public record Hit(String type, UUID id, String title, String subtitle, String path) {
    }

    public record Result(String query, List<Hit> items, List<String> searched) {
    }

    static final int PER_GROUP = 5;
    private final AuthorizationService authz;
    private final OrderService orders;
    private final CustomerService customers;
    private final SupplierService suppliers;
    private final ProductService products;
    private final VehicleService vehicles;
    private final TripService trips;

    public SearchService(AuthorizationService authz, OrderService orders, CustomerService customers,
                         SupplierService suppliers, ProductService products, VehicleService vehicles, TripService trips) {
        this.authz = authz;
        this.orders = orders;
        this.customers = customers;
        this.suppliers = suppliers;
        this.products = products;
        this.vehicles = vehicles;
        this.trips = trips;
    }

    private boolean can(Actor actor, String resource) {
        return authz.decide(actor, resource, "view").allowed();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public Result search(Actor actor, String raw) {
        String q = SearchText.normalize(raw);
        if (q == null || q.length() < 2) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Digite pelo menos 2 caracteres para buscar.");
        }
        List<Hit> hits = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        if (can(actor, "orders.pickup") || can(actor, "orders.delivery")) {
            searched.add("orders");
            for (var o : orders.list(actor, null, null, q, PER_GROUP, null).items()) {
                hits.add(new Hit("order", o.id(), "Pedido #" + o.number() + " · " + o.customer_name(),
                        (o.type().equals("DELIVERY") ? "Entrega" : "Retirada") + " · " + o.state() + " · R$ " + o.total(),
                        "/sales/orders/" + o.id()));
            }
        }
        if (can(actor, "trips")) {
            searched.add("trips");
            for (var t : trips.list(actor, null, q, PER_GROUP, null).items()) {
                hits.add(new Hit("trip", t.id(), "Viagem V" + t.number(),
                        (t.plate() == null ? "sem veículo" : t.plate()) + " · " + t.state(), "/operation/trips/" + t.id()));
            }
        }
        if (can(actor, "vehicles")) {
            searched.add("vehicles");
            String needle = q.replace("-", "").toUpperCase(Locale.ROOT);
            vehicles.list(actor, null).items().stream()
                    .filter(v -> v.plate().replace("-", "").toUpperCase(Locale.ROOT).contains(needle)
                            || (v.model() != null && v.model().toUpperCase(Locale.ROOT).contains(needle)))
                    .limit(PER_GROUP)
                    .forEach(v -> hits.add(new Hit("vehicle", v.id(), v.plate() + " · " + v.model(), v.status(),
                            "/operation/fleet/" + v.id())));
        }
        if (can(actor, "customers")) {
            searched.add("customers");
            for (var c : customers.list(actor, q, PER_GROUP, null).items()) {
                hits.add(new Hit("customer", c.id(), c.name(), c.branch(), "/sales/customers?q=" + enc(c.name())));
            }
        }
        if (can(actor, "suppliers")) {
            searched.add("suppliers");
            for (var s : suppliers.list(actor, q, PER_GROUP, null).items()) {
                hits.add(new Hit("supplier", s.id(), s.name(), "Fornecedor", "/operation/suppliers?q=" + enc(s.name())));
            }
        }
        if (can(actor, "catalog")) {
            searched.add("products");
            for (var p : products.list(actor, q, PER_GROUP, null).items()) {
                hits.add(new Hit("product", p.id(), p.name(), p.sku() + " · " + p.base_unit(),
                        "/operation/products?q=" + enc(p.sku())));
            }
        }
        if (searched.isEmpty()) {
            throw ApiException.forbidden();
        }
        return new Result(q, hits, searched);
    }
}
