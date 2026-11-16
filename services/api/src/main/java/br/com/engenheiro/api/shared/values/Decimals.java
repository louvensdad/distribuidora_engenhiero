package br.com.engenheiro.api.shared.values;

import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Quantidades e dinheiro chegam como string decimal ("125.30"), nunca float (cerebro/03).
 * Quantidade: até 6 casas; dinheiro: até 2 casas; arredondamento monetário HALF_UP.
 */
public final class Decimals {
    public static final int QTY_SCALE = 6;
    public static final int MONEY_SCALE = 2;
    private static final BigDecimal MAX = new BigDecimal("1000000000000");

    private Decimals() {
    }

    /** Lê decimal; registra erro de campo e devolve null se inválido. `allowZero` false exige > 0. */
    public static BigDecimal parse(String raw, int maxScale, boolean allowZero, String field, List<FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(new FieldError(field, "Obrigatório"));
            return null;
        }
        if (!raw.strip().matches("[0-9]{1,13}(\\.[0-9]+)?")) {
            errors.add(new FieldError(field, "Use número decimal com ponto, ex.: 12.5"));
            return null;
        }
        BigDecimal v = new BigDecimal(raw.strip());
        if (v.stripTrailingZeros().scale() > maxScale) {
            errors.add(new FieldError(field, "No máximo " + maxScale + " casas decimais"));
            return null;
        }
        if (v.compareTo(MAX) >= 0 || (!allowZero && v.signum() == 0)) {
            errors.add(new FieldError(field, allowZero ? "Valor fora do limite" : "Deve ser maior que zero"));
            return null;
        }
        return v;
    }

    public static BigDecimal parseOptional(String raw, int maxScale, String field, List<FieldError> errors) {
        return raw == null || raw.isBlank() ? null : parse(raw, maxScale, true, field, errors);
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Representação para o contrato: string sem notação científica e sem zeros à direita além do necessário. */
    public static String str(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    public static String moneyStr(BigDecimal v) {
        return v == null ? null : money(v).toPlainString();
    }
}
