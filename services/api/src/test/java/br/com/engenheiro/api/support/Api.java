package br.com.engenheiro.api.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cliente HTTP real contra a porta aleatória do servidor de teste. */
public final class Api {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;

    public Api(int port) {
        this.base = "http://localhost:" + port;
    }

    public record Res(int status, String body, Map<String, java.util.List<String>> headers) {
        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    public Res get(String path, String token) {
        return send(req(path, token).GET().build());
    }

    public Res post(String path, String token, Object body, String ifMatch) {
        HttpRequest.Builder b = req(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return send(b.build());
    }

    /** POST com cabeçalhos extras (Idempotency-Key, If-Match). */
    public Res postWith(String path, String token, Object body, Map<String, String> headers) {
        HttpRequest.Builder b = req(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : JSON.writeValueAsString(body)));
        headers.forEach(b::header);
        return send(b.build());
    }

    /** Envio multipart com um único arquivo no campo "file". */
    public Res upload(String path, String token, String fileName, String contentType, byte[] bytes) {
        String boundary = "----engenheiro" + System.nanoTime();
        var out = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n";
        out.writeBytes(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.writeBytes(bytes);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return send(req(path, token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build());
    }

    public Res getBytes(String path, String token) {
        try {
            HttpResponse<byte[]> res = http.send(req(path, token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Res(res.statusCode(), new String(res.body(), java.nio.charset.StandardCharsets.ISO_8859_1),
                    res.headers().map());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder req(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private Res send(HttpRequest r) {
        try {
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            return new Res(res.statusCode(), res.body(), res.headers().map());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
