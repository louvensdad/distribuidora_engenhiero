package br.com.engenheiro.api.shared.ops;

import java.util.regex.Pattern;

/**
 * Limpa texto antes de ir para o log (F16, OPS-03): tokens (JWT/Bearer), segredos em pares chave=valor, e-mail,
 * CPF/CNPJ e qualquer sequência longa de dígitos (documento, conta, chave Pix numérica). Mensagens do banco trazem
 * os valores da linha ("Key (cpf)=(…)") — por isso o detalhe entre parênteses depois de "Key" também some.
 */
public final class LogSanitizer {
    private static final String MASK = "•••";
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(password|senha|secret|segredo|token|api[_-]?key|client[_-]?secret|authorization)\\b(\\s*[=:]\\s*)\\S+");
    private static final Pattern PG_DETAIL = Pattern.compile("(?i)(Key \\([^)]*\\))=\\([^)]*\\)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern DOC = Pattern.compile(
            "\\b\\d{2,3}\\.?\\d{3}\\.?\\d{3}(/?\\d{4})?-?\\d{2}\\b|\\b\\d{8,}\\b");

    private LogSanitizer() {
    }

    public static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = JWT.matcher(s).replaceAll(MASK);
        out = BEARER.matcher(out).replaceAll("Bearer " + MASK);
        out = SECRET_PAIR.matcher(out).replaceAll("$1$2" + MASK);
        out = PG_DETAIL.matcher(out).replaceAll("$1=(" + MASK + ")");
        out = EMAIL.matcher(out).replaceAll(MASK);
        out = DOC.matcher(out).replaceAll(MASK);
        return out.length() > 500 ? out.substring(0, 500) + "…" : out;
    }

    /**
     * Cópia da exceção só com tipos e pilha (mensagens limpas), para o log manter o diagnóstico sem os dados que a
     * mensagem original carregava.
     */
    public static Throwable scrub(Throwable t) {
        return scrub(t, 0);
    }

    private static Throwable scrub(Throwable t, int depth) {
        if (t == null || depth > 8) {
            return null;
        }
        RuntimeException copy = new RuntimeException(t.getClass().getName() + ": " + clean(t.getMessage()),
                scrub(t.getCause(), depth + 1), false, true) {
        };
        copy.setStackTrace(t.getStackTrace());
        return copy;
    }
}
