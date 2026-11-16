package br.com.engenheiro.api.shared.web;

import java.util.List;

/** Versão esperada a partir do cabeçalho If-Match (ETag "<versão>"). */
public final class IfMatch {
    private IfMatch() {
    }

    public static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Cabeçalho If-Match obrigatório.",
                    List.of(new ApiProblem.FieldError("If-Match", "Informe a versão (ETag) lida")));
        }
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "If-Match inválido.");
        }
    }
}
