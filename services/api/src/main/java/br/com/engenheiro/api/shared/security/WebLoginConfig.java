package br.com.engenheiro.api.shared.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Client confidencial do site. Tokens ficam na sessão do servidor (HttpSession), nunca no navegador.
 * Só é criado quando há client-secret configurado.
 */
@Configuration
@ConditionalOnExpression("'${engenheiro.web.client-secret:}' != ''")
public class WebLoginConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(WebLoginProperties web, OidcProperties oidc) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(WebLoginProperties.REGISTRATION_ID)
                .clientId(web.clientId())
                .clientSecret(web.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri(web.authorizationUri())
                .tokenUri(web.tokenUri())
                .jwkSetUri(oidc.jwkSetUri())
                .issuerUri(oidc.issuer())
                .userNameAttributeName("sub")
                .providerConfigurationMetadata(java.util.Map.of("end_session_endpoint", web.endSessionUri()))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Renova o access token pela sessão quando expira (refresh token também só no servidor). */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientRepository clients) {
        DefaultOAuth2AuthorizedClientManager manager = new DefaultOAuth2AuthorizedClientManager(registrations,
                clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode().refreshToken().build());
        return manager;
    }
}
