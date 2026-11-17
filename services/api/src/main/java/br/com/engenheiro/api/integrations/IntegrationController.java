package br.com.engenheiro.api.integrations;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** F08 — notas fiscais, cobrança Pix por provedor, avisos assinados e estado das integrações. */
@RestController
public class IntegrationController {
    private static final String IDEM = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private final InvoiceService invoices;
    private final PaymentIntegrationService payments;
    private final IntegrationStatusService status;

    public IntegrationController(InvoiceService invoices, PaymentIntegrationService payments,
                                 IntegrationStatusService status) {
        this.invoices = invoices;
        this.payments = payments;
        this.status = status;
    }

    // ---- configuração fiscal ----
    @GetMapping("/api/v1/fiscal-settings")
    public ResponseEntity<InvoiceService.SettingsView> settings(Actor actor) {
        return ok(invoices.settings(actor));
    }

    @PutMapping("/api/v1/fiscal-settings")
    public ResponseEntity<InvoiceService.SettingsView> updateSettings(Actor actor,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody InvoiceService.SettingsCommand body) {
        return ok(invoices.updateSettings(actor, IfMatch.version(ifMatch), body));
    }

    @PutMapping("/api/v1/products/{id}/fiscal-data")
    public ResponseEntity<InvoiceService.ProductFiscalView> productFiscal(Actor actor, @PathVariable UUID id,
            @RequestBody InvoiceService.ProductFiscalCommand body) {
        return ok(invoices.updateProductFiscal(actor, id, body));
    }

    // ---- notas ----
    @GetMapping("/api/v1/invoices")
    public ResponseEntity<Map<String, List<InvoiceService.InvoiceView>>> list(Actor actor,
            @RequestParam(required = false) String state) {
        return ok(Map.of("items", invoices.list(actor, state)));
    }

    @PostMapping("/api/v1/invoices")
    public ResponseEntity<InvoiceService.InvoiceView> create(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                                            @RequestBody InvoiceService.CreateCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(invoices.create(actor, key, body));
    }

    @GetMapping("/api/v1/invoices/{id}")
    public ResponseEntity<InvoiceService.InvoiceView> get(Actor actor, @PathVariable UUID id) {
        return ok(invoices.getView(actor, id));
    }

    @PostMapping("/api/v1/invoices/{id}/review")
    public ResponseEntity<InvoiceService.InvoiceView> review(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        return ok(invoices.review(actor, id, IfMatch.version(ifMatch)));
    }

    @PostMapping("/api/v1/invoices/{id}/transmission")
    public ResponseEntity<InvoiceService.InvoiceView> transmit(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        InvoiceService.InvoiceView v = invoices.transmit(actor, id, key, IfMatch.version(ifMatch));
        // Resultado incerto/em curso: 202 (aceito, sem conclusão) — a interface não pode mostrar como emitida.
        return ResponseEntity.status(List.of("UNCERTAIN", "TRANSMITTING").contains(v.state()) ? 202 : 200)
                .cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/api/v1/invoices/{id}/resolution")
    public ResponseEntity<InvoiceService.InvoiceView> resolve(Actor actor, @PathVariable UUID id) {
        return ok(invoices.resolve(actor, id));
    }

    @PostMapping("/api/v1/invoices/{id}/cancellation")
    public ResponseEntity<InvoiceService.InvoiceView> cancel(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key, @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody InvoiceService.ReasonCommand body) {
        InvoiceService.InvoiceView v = invoices.cancel(actor, id, key, IfMatch.version(ifMatch), body);
        return ResponseEntity.status(v.state().equals("CANCEL_PENDING") ? 202 : 200).cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/api/v1/invoices/{id}/discard")
    public ResponseEntity<InvoiceService.InvoiceView> discard(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody InvoiceService.ReasonCommand body) {
        return ok(invoices.discard(actor, id, IfMatch.version(ifMatch), body));
    }

    // ---- pagamentos por provedor ----
    @PostMapping("/api/v1/payments/{id}/charge")
    public ResponseEntity<PaymentIntegrationService.ChargeView> charge(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key) {
        return ok(payments.createCharge(actor, id, key));
    }

    /** Aviso do provedor: sem sessão/token; a autorização é a assinatura sobre os bytes exatos do corpo. */
    @PostMapping("/webhooks/payments/{provider}")
    public ResponseEntity<Map<String, String>> webhook(@PathVariable String provider, HttpServletRequest request,
                                                       @RequestBody(required = false) byte[] body) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            headers.put(n.toLowerCase(Locale.ROOT), request.getHeader(n));
        }
        PaymentIntegrationService.WebhookResult r = payments.receive(provider, headers, body == null ? new byte[0] : body);
        return ResponseEntity.status(r.httpStatus()).cacheControl(CacheControl.noStore())
                .body(Map.of("status", r.status(), "detail", r.detail()));
    }

    // ---- estado e triagem ----
    @GetMapping("/api/v1/integrations")
    public ResponseEntity<IntegrationStatusService.Status> status(Actor actor) {
        return ok(status.status(actor));
    }

    @GetMapping("/api/v1/integrations/webhook-events")
    public ResponseEntity<Map<String, List<PaymentIntegrationService.WebhookEventView>>> events(Actor actor,
            @RequestParam(required = false) String status) {
        return ok(Map.of("items", payments.events(actor, status)));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
