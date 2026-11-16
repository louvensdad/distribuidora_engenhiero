package br.com.engenheiro.api.shared.values;

/** Normalização de texto de entrada. */
public final class Text {
    private Text() {
    }

    public static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** CPF/CNPJ só com dígitos (máscara removida); null se vazio. */
    public static String document(String s) {
        String t = trim(s);
        return t == null ? null : t.replaceAll("[.\\-/\\s]", "");
    }
}
