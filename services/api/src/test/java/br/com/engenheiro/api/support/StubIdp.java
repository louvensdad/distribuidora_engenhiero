package br.com.engenheiro.api.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provedor OIDC mínimo para testes do BFF: JWKS, endpoint de token com verificação de PKCE S256,
 * autenticação do client e refresh token. Os tokens são assinados com {@link TestJwt#KEYS}.
 */
public final class StubIdp {
    public static final String CLIENT_ID = "engenheiro-web";
    public static final String CLIENT_SECRET = "segredo-de-teste-nao-real";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Pending(String subject, String nonce, String codeChallenge) {
    }

    private final HttpServer server;
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
    public final AtomicInteger refreshCalls = new AtomicInteger();
    public volatile int accessTokenTtlSeconds = 300;
    public volatile boolean pkceVerified;

    public StubIdp() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/jwks", ex -> respond(ex, 200, new JWKSet(new RSAKey.Builder(TestJwt.publicKey())
                .keyID("teste").build()).toString(true)));
        server.createContext("/token", this::token);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** O "usuário" autenticou no IdP: registra o código para a troca. */
    public String issueCode(String subject, String nonce, String codeChallenge) {
        String code = UUID.randomUUID().toString();
        codes.put(code, new Pending(subject, nonce, codeChallenge));
        return code;
    }

    public void revokeAllRefreshTokens() {
        refreshTokens.clear();
    }

    private void token(HttpExchange ex) throws IOException {
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expectedAuth.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            respond(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        Map<String, String> form = parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String subject;
        String nonce = null;
        if ("authorization_code".equals(form.get("grant_type"))) {
            Pending p = codes.remove(form.get("code"));
            if (p == null || form.get("code_verifier") == null
                    || !p.codeChallenge().equals(s256(form.get("code_verifier")))) {
                respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            pkceVerified = true;
            subject = p.subject();
            nonce = p.nonce();
        } else if ("refresh_token".equals(form.get("grant_type"))) {
            refreshCalls.incrementAndGet();
            subject = refreshTokens.remove(form.get("refresh_token"));
            if (subject == null) {
                respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
        } else {
            respond(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        String access = TestJwt.builder(subject, CLIENT_ID)
                .expiresAt(Instant.now().plusSeconds(accessTokenTtlSeconds)).sign();
        TestJwt.Builder id = TestJwt.builder(subject, CLIENT_ID).audience(List.of(CLIENT_ID))
                .claim("name", "Usuário de Teste");
        if (nonce != null) {
            id.claim("nonce", nonce);
        }
        String refresh = UUID.randomUUID().toString();
        refreshTokens.put(refresh, subject);
        Map<String, Object> body = new HashMap<>();
        body.put("access_token", access);
        body.put("token_type", "Bearer");
        body.put("expires_in", accessTokenTtlSeconds);
        body.put("refresh_token", refresh);
        body.put("id_token", id.sign());
        body.put("scope", "openid profile");
        respond(ex, 200, JSON.writeValueAsString(body));
    }

    private static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, String> parse(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return m;
        }
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
