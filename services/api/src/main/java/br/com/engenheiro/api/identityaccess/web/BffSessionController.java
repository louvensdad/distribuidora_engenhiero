package br.com.engenheiro.api.identityaccess.web;

import br.com.engenheiro.api.shared.security.WebLoginProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado da sessão do site. Não devolve tokens. Também emite o cookie XSRF-TOKEN que o site ecoa no
 * cabeçalho X-XSRF-TOKEN das mutações.
 */
@RestController
public class BffSessionController {
    private final WebLoginProperties web;

    public BffSessionController(WebLoginProperties web) {
        this.web = web;
    }

    @GetMapping("/bff/session")
    public ResponseEntity<Map<String, Object>> session(Authentication auth, CsrfToken csrf) {
        csrf.getToken(); // materializa o cookie de CSRF
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("web_login", web.configured() ? "CONFIGURADO" : "NAO_CONFIGURADO");
        boolean authenticated = auth instanceof OAuth2AuthenticationToken;
        body.put("authenticated", authenticated);
        if (web.configured()) {
            body.put("login_url", "/oauth2/authorization/" + WebLoginProperties.REGISTRATION_ID);
        }
        if (authenticated && auth.getPrincipal() instanceof OidcUser user) {
            body.put("display_name", user.getFullName() != null ? user.getFullName() : user.getSubject());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
