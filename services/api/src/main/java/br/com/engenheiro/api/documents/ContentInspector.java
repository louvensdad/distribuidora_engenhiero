package br.com.engenheiro.api.documents;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validação real de conteúdo (não confia no Content-Type nem na extensão). Aceita JPEG, PNG e PDF;
 * recusa PDF com conteúdo ativo visível (JavaScript, Launch, arquivos embutidos, XFA). Sem antivírus configurado,
 * isso é registrado em `scan_state` — não é apresentado como varredura completa.
 */
final class ContentInspector {
    record Result(String mime, String rejection) {
    }

    private static final List<Pattern> PDF_ACTIVE = List.of(
            Pattern.compile("/JavaScript\\b"), Pattern.compile("/JS\\b"), Pattern.compile("/Launch\\b"),
            Pattern.compile("/EmbeddedFile\\b"), Pattern.compile("/RichMedia\\b"), Pattern.compile("/XFA\\b"));

    private ContentInspector() {
    }

    static Result inspect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            boolean end = b.length >= 2 && (b[b.length - 2] & 0xFF) == 0xFF && (b[b.length - 1] & 0xFF) == 0xD9;
            return new Result("image/jpeg", end ? null : "JPEG incompleto ou corrompido");
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D
                && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            String tail = new String(b, Math.max(0, b.length - 16), Math.min(16, b.length), StandardCharsets.ISO_8859_1);
            return new Result("image/png", tail.contains("IEND") ? null : "PNG incompleto ou corrompido");
        }
        if (b.length >= 5 && new String(b, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-")) {
            String text = new String(b, StandardCharsets.ISO_8859_1);
            for (Pattern p : PDF_ACTIVE) {
                if (p.matcher(text).find()) {
                    return new Result("application/pdf", "PDF com conteúdo ativo (" + p.pattern().replace("\\b", "") + ")");
                }
            }
            return new Result("application/pdf", text.contains("%%EOF") ? null : "PDF incompleto ou corrompido");
        }
        return new Result(null, "Tipo não aceito: envie JPEG, PNG ou PDF");
    }
}
