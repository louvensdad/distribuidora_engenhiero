package br.com.engenheiro.api.shared.web;

/** Texto de busca: aparado, 2 a 80 caracteres; curingas do LIKE escapados (busca literal, sem padrão do usuário). */
public final class SearchText {
    private SearchText() {
    }

    /** null quando vazio; 400 quando curto/longo demais. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String q = raw.strip().replaceAll("\\s+", " ");
        if (q.length() > 80) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Busca com no máximo 80 caracteres.");
        }
        return q;
    }

    /** Escapa \, % e _ para uso com ESCAPE '\'. */
    public static String like(String q) {
        return q == null ? null : q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
