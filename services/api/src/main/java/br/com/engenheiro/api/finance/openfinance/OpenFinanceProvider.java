package br.com.engenheiro.api.finance.openfinance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Agregador de Open Finance (instituição autorizada que, com o consentimento do representante da empresa em cada
 * banco, entrega saldo e extrato). Nenhuma implementação vem no produto: sem agregador contratado o serviço responde
 * NÃO CONFIGURADO (ADR-021). Uma implementação real (ex.: Pluggy, Belvo) entra como @Component com credenciais por
 * variável de ambiente, sem segredo no repositório.
 */
public interface OpenFinanceProvider {
    String name();

    /** Credenciais e contrato presentes. */
    boolean configured();

    /** Inicia o consentimento da empresa (CNPJ) na instituição; devolve o id no agregador e a URL para autorizar. */
    Consent startConsent(String companyDocument, String institution, String redirectUri);

    /** Estado do consentimento, saldo atual e transações desde a data (inclusive). */
    Snapshot fetch(String externalId, LocalDate since);

    void revoke(String externalId);

    record Consent(String externalId, String consentUrl) {
    }

    /** direction: IN (crédito) ou OUT (débito); amount sempre positivo; id estável no agregador. */
    record Tx(String id, LocalDate bookedOn, BigDecimal amount, String direction, String description, String counterpart) {
    }

    /** state: PENDING_CONSENT, ACTIVE, REVOKED, EXPIRED ou ERROR. */
    record Snapshot(String state, BigDecimal balance, List<Tx> transactions) {
    }
}
