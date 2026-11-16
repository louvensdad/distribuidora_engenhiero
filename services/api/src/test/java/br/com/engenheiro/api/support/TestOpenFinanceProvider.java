package br.com.engenheiro.api.support;

import br.com.engenheiro.api.finance.openfinance.OpenFinanceProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Agregador FALSO, só no código de teste (não existe no produto). Desligado por padrão: os testes que não o ligam
 * veem o estado real NÃO CONFIGURADO. Quando ligado, devolve o extrato que o teste montar.
 */
@Component
public class TestOpenFinanceProvider implements OpenFinanceProvider {
    public static volatile boolean enabled = false;
    public static volatile String state = "ACTIVE";
    public static volatile BigDecimal balance = BigDecimal.ZERO;
    public static final List<Tx> TRANSACTIONS = new ArrayList<>();

    public static void reset() {
        enabled = false;
        state = "ACTIVE";
        balance = BigDecimal.ZERO;
        TRANSACTIONS.clear();
    }

    @Override
    public String name() {
        return "teste";
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    @Override
    public Consent startConsent(String companyDocument, String institution, String redirectUri) {
        return new Consent("item-" + companyDocument, "https://agregador.teste.invalid/consent/" + companyDocument);
    }

    @Override
    public Snapshot fetch(String externalId, LocalDate since) {
        return new Snapshot(state, balance, List.copyOf(TRANSACTIONS));
    }

    @Override
    public void revoke(String externalId) {
    }
}
