package br.com.engenheiro.api.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** Navegador simplificado: guarda cookies, não segue redirecionamentos, não tem acesso a tokens. */
public final class Browser {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;

    public Browser(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String pathOrUrl) {
        return send(HttpRequest.newBuilder(uri(pathOrUrl)).GET().build());
    }

    public HttpResponse<String> post(String path, String json, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return send(b.build());
    }

    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    public List<HttpCookie> allCookies() {
        return cookies.getCookieStore().getCookies();
    }

    private URI uri(String pathOrUrl) {
        return URI.create(pathOrUrl.startsWith("http") ? pathOrUrl : base + pathOrUrl);
    }

    private HttpResponse<String> send(HttpRequest r) {
        try {
            return http.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
