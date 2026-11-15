package br.com.engenheiro.api.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Emite JWTs RS256 reais para os testes (o decoder valida assinatura, emissor, audiência e validade). */
public final class TestJwt {
    public static final String ISSUER = "https://idp.teste.invalid/realms/engenheiro";
    public static final String AUDIENCE = "engenheiro-api";
    public static final KeyPair KEYS = generate();
    public static final KeyPair OTHER_KEYS = generate();

    private TestJwt() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    public static String token(String subject, String clientId) {
        return builder(subject, clientId).sign();
    }

    public static Builder builder(String subject, String clientId) {
        return new Builder(subject, clientId);
    }

    public static final class Builder {
        private final String subject;
        private final String clientId;
        private String issuer = ISSUER;
        private List<String> audience = List.of(AUDIENCE, "account");
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private KeyPair keys = KEYS;
        private final java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();

        private Builder(String subject, String clientId) {
            this.subject = subject;
            this.clientId = clientId;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(List<String> audience) {
            this.audience = audience;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder claim(String name, Object value) {
            extra.put(name, value);
            return this;
        }

        public Builder signedWith(KeyPair keys) {
            this.keys = keys;
            return this;
        }

        public String sign() {
            try {
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                        .issuer(issuer).subject(subject).audience(audience).claim("azp", clientId)
                        .issueTime(Date.from(Instant.now().minusSeconds(5)))
                        .expirationTime(Date.from(expiresAt)).jwtID(UUID.randomUUID().toString());
                extra.forEach(claims::claim);
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("teste").build(),
                        claims.build());
                jwt.sign(new RSASSASigner(keys.getPrivate()));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
