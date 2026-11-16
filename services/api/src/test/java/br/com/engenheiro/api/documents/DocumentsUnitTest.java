package br.com.engenheiro.api.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentsUnitTest {
    @Test
    void semArmazenamentoConfiguradoNaoSimulaSucesso() {
        DocumentService s = new DocumentService(null, new LocalObjectStorage(""), null);
        assertThatThrownBy(() -> s.attach(null, null, "inventory_receipt", null, "a.png", new byte[]{1}))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTEGRATION_UNAVAILABLE))
                .hasMessageContaining("NÃO CONFIGURADO");
    }

    @Test
    void inspecionaPeloConteudoENaoPeloNome() {
        assertThat(ContentInspector.inspect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, (byte) 0xFF, (byte) 0xD9}))
                .isEqualTo(new ContentInspector.Result("image/jpeg", null));
        assertThat(ContentInspector.inspect("%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(new ContentInspector.Result("application/pdf", null));
        assertThat(ContentInspector.inspect("%PDF-1.4 /Launch /F (cmd.exe) %%EOF".getBytes(StandardCharsets.ISO_8859_1))
                .rejection()).contains("Launch");
        assertThat(ContentInspector.inspect("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)).rejection())
                .contains("Tipo não aceito");
        assertThat(ContentInspector.inspect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}).rejection())
                .contains("incompleto");
    }

    @Test
    void extensaoSegueOConteudoReal() {
        assertThat(DocumentService.withExtension("scaled_20.png", "image/jpeg")).isEqualTo("scaled_20.jpg");
        assertThat(DocumentService.withExtension("foto.JPEG", "image/jpeg")).isEqualTo("foto.JPEG");
        assertThat(DocumentService.withExtension("nota", "application/pdf")).isEqualTo("nota.pdf");
    }

    @Test
    void nomeDeArquivoSemCaminho() {
        assertThat(DocumentService.sanitize("../../etc/passwd")).doesNotContain("/");
        assertThat(DocumentService.sanitize("  ")).isEqualTo("anexo");
    }
}
