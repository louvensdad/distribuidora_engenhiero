package br.com.engenheiro.api.shared.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Emissor, JWKS e audiência esperados para tokens de acesso. Nenhum segredo aqui. */
@Validated
@ConfigurationProperties("engenheiro.security.oidc")
public record OidcProperties(@NotBlank String issuer, @NotBlank String jwkSetUri, @NotBlank String audience) {
}
