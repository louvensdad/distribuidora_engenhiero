package br.com.engenheiro.api.catalogparties.products;

import java.util.List;

/** Medidas aceitas. Nenhuma equivalência universal: fator sempre por produto (cerebro/03, D01). */
public final class Units {
    public static final List<String> ALL = List.of("UNIDADE", "DUZIA", "FEIXE", "MACO", "CAIXA", "SACO", "KG",
            "TONELADA");

    private Units() {
    }

    public static boolean valid(String unit) {
        return unit != null && ALL.contains(unit);
    }
}
