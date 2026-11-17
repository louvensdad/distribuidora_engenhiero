package br.com.engenheiro.api.documents;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.inventory.ReceiptService;
import br.com.engenheiro.api.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Download de anexo: autoriza pelo registro dono a cada pedido; resposta não armazenável e sem sniffing. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documents;
    private final ReceiptService receipts;
    private final br.com.engenheiro.api.logistics.TripService trips;
    private final br.com.engenheiro.api.logistics.VehicleExpenseService expenses;
    private final br.com.engenheiro.api.finance.ExpenseService finExpenses;
    private final br.com.engenheiro.api.accounting.TaxObligationService taxes;
    private final br.com.engenheiro.api.accounting.AccountingDocumentService accountingDocs;

    public DocumentController(DocumentService documents, ReceiptService receipts,
                              br.com.engenheiro.api.logistics.TripService trips,
                              br.com.engenheiro.api.logistics.VehicleExpenseService expenses,
                              br.com.engenheiro.api.finance.ExpenseService finExpenses,
                              br.com.engenheiro.api.accounting.TaxObligationService taxes,
                              br.com.engenheiro.api.accounting.AccountingDocumentService accountingDocs) {
        this.taxes = taxes;
        this.accountingDocs = accountingDocs;
        this.finExpenses = finExpenses;
        this.documents = documents;
        this.receipts = receipts;
        this.trips = trips;
        this.expenses = expenses;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(Actor actor, @PathVariable UUID id) {
        DocumentService.Stored d = documents.find(id).filter(x -> x.organizationId().equals(actor.organizationId()))
                .orElseThrow(ApiException::notFound);
        switch (d.ownerType()) {
            case "inventory_receipt" -> receipts.get(actor, d.ownerId()); // 403/404 conforme a política do recebimento
            case "trip_stop" -> trips.requireStopVisible(actor, d.ownerId());
            case "vehicle_expense" -> expenses.get(actor, d.ownerId());
            case "expense" -> finExpenses.get(actor, d.ownerId());
            case "tax_obligation" -> taxes.requireVisible(actor, d.ownerId());
            case "accounting_document" -> accountingDocs.requireVisible(actor, d.ownerId());
            default -> throw ApiException.notFound();
        }
        DocumentService.Content c = documents.read(actor, d);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(c.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(c.bytes());
    }
}
