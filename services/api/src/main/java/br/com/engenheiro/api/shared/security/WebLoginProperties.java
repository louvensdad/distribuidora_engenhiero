package br.com.engenheiro.api.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Login do site via BFF. O segredo vem só do ambiente/secret manager. */
@ConfigurationProperties("engenheiro.web")
public record WebLoginProperties(String clientId, String clientSecret, String appUrl, String authorizationUri,
                                 String tokenUri, String endSessionUri) {
    public static final String REGISTRATION_ID = "engenheiro";

    public boolean configured() {
        return clientSecret != null && !clientSecret.isBlank();
    }
}
