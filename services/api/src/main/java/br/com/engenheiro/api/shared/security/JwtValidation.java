package br.com.engenheiro.api.shared.security;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Assinatura (pelo decoder) + emissor + expiração/nbf + audiência da API. */
public final class JwtValidation {
    private JwtValidation() {
    }

    public static OAuth2TokenValidator<Jwt> validators(OidcProperties props) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(props.audience()));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer()), audience);
    }
}
