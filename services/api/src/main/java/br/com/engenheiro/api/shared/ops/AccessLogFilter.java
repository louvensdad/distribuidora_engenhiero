package br.com.engenheiro.api.shared.ops;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.security.ActorFilter;
import br.com.engenheiro.api.shared.web.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Uma linha por requisição (F16, OPS-03): método, rota com identificadores trocados por ":id" e SEM query string
 * (busca por nome/CPF nunca entra no log), status, latência, request_id, app e ator pseudonimizado (HMAC do id com
 * um segredo de servidor — correlaciona sem identificar). Nada de token, corpo, cabeçalho ou parâmetro.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("engenheiro.acesso");
    private static final Pattern UUID_SEG = Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NUM_SEG = Pattern.compile("/\\d+(?=/|$)");
    private static final Pattern OTHER = Pattern.compile("[^A-Za-z0-9/._:-]");
    private final byte[] pepper;

    public AccessLogFilter(@Value("${engenheiro.log.pepper:}") String pepper) {
        this.pepper = (pepper == null || pepper.isEmpty() ? "pseudonimo-local-nao-usar-em-producao" : pepper)
                .getBytes(StandardCharsets.UTF_8);
    }

    static String route(String uri) {
        String r = UUID_SEG.matcher(uri == null ? "" : uri).replaceAll("/:id");
        r = NUM_SEG.matcher(r).replaceAll("/:n");
        r = OTHER.matcher(r).replaceAll("_");
        return r.length() > 120 ? r.substring(0, 120) : r;
    }

    String pseudonym(UUID userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(userId.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object a = request.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
            String actor = a instanceof Actor act ? pseudonym(act.userId()) : "-";
            String client = a instanceof Actor act ? act.clientId() : "-";
            MDC.put("ator", actor);
            try {
                log.info("acesso metodo={} rota={} status={} ms={} request_id={} app={} ator={}", request.getMethod(),
                        route(request.getRequestURI()), response.getStatus(), (System.nanoTime() - start) / 1_000_000,
                        RequestIds.current(), client, actor);
            } finally {
                MDC.remove("ator");
            }
        }
    }
}
