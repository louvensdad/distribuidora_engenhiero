package br.com.engenheiro.api.inventory;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.inventory.LossService.DecisionCommand;
import br.com.engenheiro.api.inventory.LossService.LossView;
import br.com.engenheiro.api.inventory.ReceiptService.ConfirmCommand;
import br.com.engenheiro.api.inventory.ReceiptService.CreateCommand;
import br.com.engenheiro.api.inventory.ReceiptService.ReceiptView;
import br.com.engenheiro.api.inventory.ReceiptService.ReturnCommand;
import br.com.engenheiro.api.inventory.ReceiptService.ReturnResult;
import br.com.engenheiro.api.inventory.StockQueryService.BalancePage;
import br.com.engenheiro.api.inventory.StockQueryService.MovementPage;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.net.URI;
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

/** Estoque: recebimentos, saldos, movimentos e perdas. Comandos exigem Idempotency-Key. */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private static final String IDEM = "Idempotency-Key";
    private final ReceiptService receipts;
    private final LossService losses;
    private final StockQueryService stock;
    private final AdjustmentService adjustments;

    public InventoryController(ReceiptService receipts, LossService losses, StockQueryService stock,
                               AdjustmentService adjustments) {
        this.adjustments = adjustments;
        this.receipts = receipts;
        this.losses = losses;
        this.stock = stock;
    }

    @PostMapping("/receipts")
    public ResponseEntity<ReceiptView> createReceipt(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                                     @RequestBody CreateCommand body) {
        ReceiptView v = receipts.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/inventory/receipts/" + v.id()))
                .eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/receipts")
    public ResponseEntity<ReceiptService.Page> listReceipts(Actor actor, @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "25") int limit,
                                                            @RequestParam(required = false) String cursor) {
        return noStore(receipts.list(actor, status, limit, cursor));
    }

    @GetMapping("/receipts/{id}")
    public ResponseEntity<ReceiptView> getReceipt(Actor actor, @PathVariable UUID id) {
        ReceiptView v = receipts.get(actor, id);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/receipts/{id}/confirm")
    public ResponseEntity<ReceiptView> confirmReceipt(Actor actor, @PathVariable UUID id,
                                                      @RequestHeader(value = IDEM, required = false) String key,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      @RequestBody(required = false) ConfirmCommand body) {
        ReceiptView v = receipts.confirm(actor, id, key, IfMatch.version(ifMatch), body);
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/receipts/{id}/returns")
    public ResponseEntity<ReturnResult> returnToSupplier(Actor actor, @PathVariable UUID id,
                                                         @RequestHeader(value = IDEM, required = false) String key,
                                                         @RequestBody ReturnCommand body) {
        return noStore(receipts.returnToSupplier(actor, id, key, body));
    }

    @PostMapping(value = "/receipts/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<br.com.engenheiro.api.documents.DocumentService.DocumentView> attach(Actor actor,
            @PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        var v = receipts.attach(actor, id, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/receipts/{id}/attachments")
    public ResponseEntity<java.util.Map<String, Object>> attachments(Actor actor, @PathVariable UUID id) {
        return noStore(java.util.Map.of("items", receipts.attachments(actor, id)));
    }

    @GetMapping("/balances")
    public ResponseEntity<BalancePage> balances(Actor actor, @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "25") int limit,
                                                @RequestParam(required = false) String cursor) {
        return noStore(stock.balances(actor, q, limit, cursor));
    }

    @GetMapping("/balances/summary")
    public ResponseEntity<StockQueryService.Summary> balanceSummary(Actor actor) {
        return noStore(stock.summary(actor));
    }

    @GetMapping("/movements")
    public ResponseEntity<MovementPage> movements(Actor actor, @RequestParam(required = false) UUID product_id,
                                                  @RequestParam(defaultValue = "25") int limit,
                                                  @RequestParam(required = false) String cursor) {
        return noStore(stock.movements(actor, product_id, limit, cursor));
    }

    @PostMapping("/losses")
    public ResponseEntity<LossView> createLoss(Actor actor, @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestBody LossService.CreateCommand body) {
        LossView v = losses.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/inventory/losses/" + v.id()))
                .eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/losses")
    public ResponseEntity<LossService.Page> listLosses(Actor actor, @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "25") int limit,
                                                       @RequestParam(required = false) String cursor) {
        return noStore(losses.list(actor, status, limit, cursor));
    }

    @GetMapping("/losses/{id}")
    public ResponseEntity<LossView> getLoss(Actor actor, @PathVariable UUID id) {
        return loss(losses.get(actor, id));
    }

    @PostMapping("/losses/{id}/approve")
    public ResponseEntity<LossView> approveLoss(Actor actor, @PathVariable UUID id,
                                                @RequestHeader(value = IDEM, required = false) String key,
                                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return loss(losses.approve(actor, id, key, IfMatch.version(ifMatch)));
    }

    @PostMapping("/losses/{id}/reject")
    public ResponseEntity<LossView> rejectLoss(Actor actor, @PathVariable UUID id,
                                               @RequestHeader(value = IDEM, required = false) String key,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody DecisionCommand body) {
        return loss(losses.reject(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/losses/{id}/reverse")
    public ResponseEntity<LossView> reverseLoss(Actor actor, @PathVariable UUID id,
                                                @RequestHeader(value = IDEM, required = false) String key,
                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                @RequestBody DecisionCommand body) {
        return loss(losses.reverse(actor, id, key, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/adjustments")
    public ResponseEntity<AdjustmentService.AdjustmentView> createAdjustment(Actor actor,
            @RequestHeader(value = IDEM, required = false) String key, @RequestBody AdjustmentService.CreateCommand body) {
        AdjustmentService.AdjustmentView v = adjustments.create(actor, key, body);
        return ResponseEntity.created(URI.create("/api/v1/inventory/adjustments/" + v.id()))
                .eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    @GetMapping("/adjustments")
    public ResponseEntity<AdjustmentService.Page> listAdjustments(Actor actor, @RequestParam(required = false) String status,
                                                                  @RequestParam(defaultValue = "25") int limit,
                                                                  @RequestParam(required = false) String cursor) {
        return noStore(adjustments.list(actor, status, limit, cursor));
    }

    @PostMapping("/adjustments/{id}/approve")
    public ResponseEntity<AdjustmentService.AdjustmentView> approveAdjustment(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return noStore(adjustments.approve(actor, id, key, IfMatch.version(ifMatch)));
    }

    @PostMapping("/adjustments/{id}/reject")
    public ResponseEntity<AdjustmentService.AdjustmentView> rejectAdjustment(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IDEM, required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody AdjustmentService.DecisionCommand body) {
        return noStore(adjustments.reject(actor, id, key, IfMatch.version(ifMatch), body));
    }

    private static ResponseEntity<LossView> loss(LossView v) {
        return ResponseEntity.ok().eTag("\"" + v.version() + "\"").cacheControl(CacheControl.noStore()).body(v);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
