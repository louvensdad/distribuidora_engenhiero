package br.com.engenheiro.api.team;

import br.com.engenheiro.api.shared.security.OidcProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak (provedor da empresa, D01) via Admin REST com um client de serviço próprio (client credentials, papel
 * realm-management/manage-users). Segredo só em variável de ambiente (ENGENHEIRO_PROVISIONER_CLIENT_SECRET).
 * A conta nasce com senha temporária e a ação obrigatória de trocar a senha; o atributo engenheiro_employee_id
 * permite retomar com segurança uma criação cuja resposta se perdeu.
 */
@Component
public class KeycloakIdentityProvisioner implements IdentityProvisioner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final String issuer;
    /** Base do realm usada para token e administração (padrão: o emissor público). */
    private final String realmBase;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1) // sem upgrade h2c no trajeto interno
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();

    public KeycloakIdentityProvisioner(OidcProperties oidc,
                                       @Value("${engenheiro.provisioner.client-id:}") String clientId,
                                       @Value("${engenheiro.provisioner.client-secret:}") String clientSecret,
                                       @Value("${engenheiro.provisioner.internal-url:}") String internalUrl) {
        this.issuer = oidc.issuer();
        // F17: a administração do IdP fica fora da borda pública; em servidor, a API fala com o realm pela rede interna
        // (ex.: http://keycloak:8080/realms/engenheiro). Os tokens continuam emitidos com o emissor público.
        this.realmBase = internalUrl == null || internalUrl.isBlank() ? oidc.issuer() : internalUrl.replaceAll("/+$", "");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String name() {
        return "keycloak";
    }

    @Override
    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && realmBase.contains("/realms/");
    }

    private String adminBase() {
        int i = realmBase.indexOf("/realms/");
        return realmBase.substring(0, i) + "/admin" + realmBase.substring(i);
    }

    private String token() {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(realmBase + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)));
        if (r.statusCode() != 200) {
            throw new Unavailable("token de serviço recusado (" + r.statusCode() + ")");
        }
        return JSON.readTree(r.body()).path("access_token").asString();
    }

    @Override
    public Provisioned createUser(String username, String displayName, String employeeRef) {
        String tok = token();
        String password = temporaryPassword();
        String[] parts = idpName(displayName, username).split(" ", 2);
        Map<String, Object> body = Map.of("username", username, "firstName", parts[0],
                "lastName", parts.length > 1 ? parts[1] : parts[0], "enabled", true,
                "requiredActions", List.of("UPDATE_PASSWORD"),
                "attributes", Map.of("engenheiro_employee_id", List.of(employeeRef)),
                "credentials", List.of(Map.of("type", "password", "value", password, "temporary", true)));
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(adminBase() + "/users"))
                .header("Authorization", "Bearer " + tok).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        if (r.statusCode() == 201) {
            String loc = r.headers().firstValue("Location").orElseThrow(() -> new Unavailable("sem Location"));
            return new Provisioned(issuer, loc.substring(loc.lastIndexOf('/') + 1), password);
        }
        if (r.statusCode() == 409) {
            // Já existe: só retoma se foi criada para ESTE funcionário (tentativa anterior sem resposta).
            HttpResponse<String> f = send(HttpRequest.newBuilder(URI.create(adminBase() + "/users?exact=true&briefRepresentation=false&username="
                    + enc(username))).header("Authorization", "Bearer " + tok).GET());
            JsonNode users = JSON.readTree(f.body());
            for (JsonNode u : users) {
                JsonNode ref = u.path("attributes").path("engenheiro_employee_id");
                if (ref.isArray() && ref.size() > 0 && ref.get(0).asString().equals(employeeRef)) {
                    String id = u.path("id").asString();
                    Map<String, Object> cred = Map.of("type", "password", "value", password, "temporary", true);
                    HttpResponse<String> reset = send(HttpRequest.newBuilder(URI.create(adminBase() + "/users/" + id + "/reset-password"))
                            .header("Authorization", "Bearer " + tok).header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cred))));
                    if (reset.statusCode() / 100 != 2) {
                        throw new Unavailable("não foi possível redefinir a senha temporária (" + reset.statusCode() + ")");
                    }
                    return new Provisioned(issuer, id, password);
                }
            }
            throw new UsernameTaken();
        }
        throw new Unavailable("provedor respondeu " + r.statusCode() + reason(r.body()));
    }

    /**
     * Nome para o IdP (só exibição na tela de login): o Keycloak recusa em nome de pessoa caracteres como ( ) < > & " $ %
     * ! # ? ; * ~ / \\ | ^ = [ ] { }. O nome completo do funcionário continua intacto no sistema.
     */
    static String idpName(String displayName, String username) {
        String n = displayName == null ? "" : displayName.replaceAll("[^\\p{L}\\p{M}\\p{N}' .,-]", " ").replaceAll("\\s+", " ").strip();
        return n.isEmpty() ? username : n;
    }

    /** Código de erro do provedor (ex.: error-person-name-invalid-character, campo) — nunca o valor enviado. */
    private static String reason(String body) {
        try {
            JsonNode j = JSON.readTree(body);
            String code = j.path("errorMessage").asString("");
            String field = j.path("field").asString("");
            return code.isEmpty() ? "" : " (" + code + (field.isEmpty() ? "" : ", campo " + field) + ")";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder b) {
        try {
            return http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrompido");
        } catch (java.io.IOException e) {
            throw new Unavailable("provedor de identidade indisponível (" + e.getClass().getSimpleName() + ")");
        }
    }

    private String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
