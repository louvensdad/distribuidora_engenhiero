package br.com.engenheiro.api.shared.security;

import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.ActorResolver;
import br.com.engenheiro.api.shared.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uma cadeia para a API com dois modos de autenticação:
 * - apps móveis: bearer token (stateless; sem CSRF, pois o navegador não anexa Authorization sozinho);
 * - site do patrão: BFF com login OIDC no servidor, sessão em cookie HttpOnly/SameSite=Lax e CSRF em mutações.
 */
@Configuration
public class SecurityConfig {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    JwtDecoder jwtDecoder(OidcProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidation.validators(props));
        return decoder;
    }

    /** CSRF obrigatório em mutações sem bearer (isto é, autenticadas por cookie de sessão). */
    static final RequestMatcher CSRF_REQUIRED = (HttpServletRequest r) -> !SAFE_METHODS.contains(r.getMethod())
            && !hasBearer(r) && !r.getRequestURI().startsWith("/webhooks/");

    static boolean hasBearer(HttpServletRequest r) {
        String h = r.getHeader("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ActorResolver resolver, AuditService audit, ProblemWriter problems,
                            JwtDecoder jwtDecoder, WebLoginProperties web, JsonMapper json,
                            ObjectProvider<ClientRegistrationRepository> registrations,
                            ObjectProvider<OAuth2AuthorizedClientRepository> authorizedClients,
                            ObjectProvider<OAuth2AuthorizedClientManager> clientManager) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/bff/session").permitAll()
                        // Avisos de provedores: sem sessão/token; a autorização é a assinatura conferida no serviço.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/payments/*").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(f -> f.changeSessionId()))
                .csrf(c -> c.spa().requireCsrfProtectionMatcher(CSRF_REQUIRED))
                .cors(c -> c.disable()) // sem CORS: o site é servido na mesma origem (proxy reverso)
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtDecoder))
                        .authenticationEntryPoint((req, res, ex) ->
                                problems.write(res, ErrorCode.UNAUTHENTICATED, "Credencial ausente ou inválida.")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                problems.write(res, ErrorCode.UNAUTHENTICATED, "Credencial ausente ou inválida."))
                        .accessDeniedHandler((req, res, ex) ->
                                problems.write(res, ErrorCode.FORBIDDEN, "Acesso negado.")))
                .addFilterAfter(new ActorFilter(resolver, audit, problems, jwtDecoder, clientManager.getIfAvailable()),
                        BearerTokenAuthenticationFilter.class);

        ClientRegistrationRepository repo = registrations.getIfAvailable();
        if (repo != null) {
            DefaultOAuth2AuthorizationRequestResolver pkce = new DefaultOAuth2AuthorizationRequestResolver(repo,
                    "/oauth2/authorization");
            pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(l -> l
                    .clientRegistrationRepository(repo)
                    .authorizedClientRepository(authorizedClients.getObject())
                    .authorizationEndpoint(ep -> ep.authorizationRequestResolver(pkce))
                    .successHandler((req, res, auth) -> res.sendRedirect(web.appUrl()))
                    .failureHandler((req, res, ex) -> res.sendRedirect(web.appUrl() + "?login=falhou")));
            http.logout(l -> l
                    .logoutUrl("/bff/logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("ENGENHEIRO_SESSION")
                    .logoutSuccessHandler((req, res, auth) -> {
                        // Devolve a URL de logout do IdP; o site navega até ela (encerra a sessão SSO).
                        String url = web.endSessionUri() + "?client_id=" + enc(web.clientId())
                                + "&post_logout_redirect_uri=" + enc(web.appUrl());
                        if (auth != null && auth.getPrincipal() instanceof OidcUser user) {
                            url += "&id_token_hint=" + enc(user.getIdToken().getTokenValue());
                        }
                        res.setStatus(200);
                        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        json.writeValue(res.getOutputStream(), Map.of("logout_url", url));
                    }));
        } else {
            http.logout(l -> l.disable());
        }
        return http.build();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
