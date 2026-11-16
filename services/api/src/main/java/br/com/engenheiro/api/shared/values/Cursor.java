package br.com.engenheiro.api.shared.values;

import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Cursor opaco (chave de ordenação + id) para paginação estável. */
public final class Cursor {
    private Cursor() {
    }

    public record Key(String sort, UUID id) {
    }

    public static String encode(String sort, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((sort + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Key decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\u0000", 2);
            return new Key(parts[0], UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor inválido.");
        }
    }

    public static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit deve estar entre 1 e 100.");
        }
    }
}
