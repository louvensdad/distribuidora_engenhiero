package br.com.engenheiro.api.integrations;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provedor de emissão fiscal (empresa que transmite NF-e/NFC-e à SEFAZ com o certificado da empresa). Nenhuma
 * implementação vem no produto: sem provedor contratado (D03) a emissão fica BLOQUEADA (ADR-023). Uma implementação
 * real entra como @Component com credenciais/certificado por variável de ambiente ou cofre, nunca no repositório.
 *
 * Contrato exigido do adaptador:
 * - {@link #submit} é idempotente pela {@code reference} (a mesma referência nunca gera duas notas no provedor);
 * - {@link #query} responde o que o provedor sabe da referência, inclusive NOT_FOUND (nunca recebeu);
 * - tempo esgotado depois de enviar lança {@link Timeout} (resultado INCERTO); falha antes de enviar lança
 *   {@link Unavailable} (nada saiu, pode tentar de novo).
 */
public interface FiscalProvider {
    String name();

    boolean configured();

    boolean supports(String documentType);

    Result submit(Payload payload);

    Result query(String reference);

    CancelResult cancel(String reference, String accessKey, String reason);

    record Payload(String reference, String documentType, String environment, int series, Issuer issuer,
                   Recipient recipient, List<Item> items, BigDecimal total) {
    }

    record Issuer(String document, String stateRegistration, String taxRegime) {
    }

    /** document pode ser nulo (consumidor não identificado na NFC-e). */
    record Recipient(String name, String document, String address) {
    }

    record Item(int lineNo, String code, String description, String ncm, String cfop, int origin, String unit,
                BigDecimal quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    /** status: AUTHORIZED, REJECTED, PROCESSING (recebida, aguardando SEFAZ) ou NOT_FOUND. */
    record Result(String status, String externalRef, Long number, Integer series, String accessKey, String protocol,
                  byte[] xml, String rejectionCode, String rejectionReason) {
    }

    /** status: CANCELLED, REJECTED ou PROCESSING. */
    record CancelResult(String status, String protocol, String reason) {
    }

    /** Enviado, sem resposta a tempo: o provedor pode ter emitido. Nunca reenviar sem consultar. */
    class Timeout extends RuntimeException {
        public Timeout(String message) {
            super(message);
        }
    }

    /** Não chegou ao provedor (conexão recusada, provedor fora): seguro tentar de novo. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
