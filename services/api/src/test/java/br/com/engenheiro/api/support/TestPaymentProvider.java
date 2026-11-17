package br.com.engenheiro.api.support;

import br.com.engenheiro.api.integrations.PaymentProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provedor de pagamentos FALSO, só no código de teste. Assinatura HMAC-SHA256 do corpo com um segredo de teste
 * gerado na execução (não é credencial real). Desligado por padrão.
 */
@Component
public class TestPaymentProvider implements PaymentProvider {
    public static volatile boolean enabled = false;
    public static final String SECRET = "segredo-de-teste-" + System.nanoTime();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void reset() {
        enabled = false;
    }

    @Override
    public String name() {
        return "pix-teste";
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    public static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(Map<String, String> headers, byte[] body) {
        String sig = headers.get("x-assinatura");
        return sig != null && MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                sign(body).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Event parse(byte[] body) {
        JsonNode n = JSON.readTree(body);
        return new Event(n.path("id").asString(null), n.path("type").asString(null), n.path("txid").asString(null),
                n.path("amount").isMissingNode() ? null : new BigDecimal(n.path("amount").asString()));
    }

    @Override
    public Charge createCharge(String reference, BigDecimal amount, String description) {
        return new Charge("tx" + reference.replace("-", "").substring(0, 26),
                "00020126580014br.gov.bcb.pix0136teste-" + reference + "5204000053039865406" + amount.toPlainString(),
                OffsetDateTime.now().plusMinutes(30));
    }
}
