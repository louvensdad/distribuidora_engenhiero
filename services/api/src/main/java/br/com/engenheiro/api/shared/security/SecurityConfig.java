package br.com.engenheiro.api.shared.security;

import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.ActorResolver;
import br.com.engenheiro.api.shared.web.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(OidcProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidation.validators(props));
        return decoder;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ActorResolver resolver, AuditService audit, ProblemWriter problems)
            throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // API somente com bearer token: sem sessão nem cookie, portanto sem superfície CSRF aqui.
                // O BFF web (cookie HttpOnly + CSRF) é uma cadeia separada — ver checkpoint F01.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> { })
                        .authenticationEntryPoint((req, res, ex) ->
                                problems.write(res, ErrorCode.UNAUTHENTICATED, "Credencial ausente ou inválida.")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                problems.write(res, ErrorCode.UNAUTHENTICATED, "Credencial ausente ou inválida."))
                        .accessDeniedHandler((req, res, ex) ->
                                problems.write(res, ErrorCode.FORBIDDEN, "Acesso negado.")))
                .addFilterAfter(new ActorFilter(resolver, audit, problems), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
