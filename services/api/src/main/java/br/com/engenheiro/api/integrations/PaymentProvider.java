package br.com.engenheiro.api.integrations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Provedor de pagamentos (banco com API Pix, adquirente). Nenhuma implementação vem no produto: sem provedor (D04)
 * a cobrança fica BLOQUEADA e vale a conferência manual autorizada (ADR-023). A implementação real verifica a
 * assinatura do aviso com o segredo do provedor (variável de ambiente), nunca guardado no repositório.
 */
public interface PaymentProvider {
    String name();

    boolean configured();

    /** Confere a assinatura do aviso sobre os bytes exatos recebidos. Deve ser resistente a comparação temporal. */
    boolean verify(Map<String, String> headers, byte[] body);

    Event parse(byte[] body);

    /** Cria a cobrança Pix; idempotente pela reference. */
    Charge createCharge(String reference, BigDecimal amount, String description);

    /** type: PAYMENT_CONFIRMED, PAYMENT_REFUNDED, PAYMENT_FAILED ou outro (ignorado). providerReference: txid. */
    record Event(String eventId, String type, String providerReference, BigDecimal amount) {
    }

    record Charge(String providerReference, String code, OffsetDateTime expiresAt) {
    }
}
