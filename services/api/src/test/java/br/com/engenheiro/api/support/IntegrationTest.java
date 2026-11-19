package br.com.engenheiro.api.support;

import br.com.engenheiro.api.shared.security.JwtValidation;
import br.com.engenheiro.api.shared.security.OidcProperties;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "engenheiro.security.oidc.issuer=" + TestJwt.ISSUER,
        "engenheiro.security.oidc.jwk-set-uri=https://idp.teste.invalid/nao-usado",
        "engenheiro.security.oidc.audience=" + TestJwt.AUDIENCE,
        "engenheiro.storage.local-dir=${java.io.tmpdir}/engenheiro-teste-objetos",
        // IA: nunca usa uma chave real do ambiente de quem roda os testes; o provedor dos testes é o de teste.
        "engenheiro.ia.provedor=teste", "engenheiro.ia.anthropic.api-key="})
@Import(IntegrationTest.Config.class)
public @interface IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6-alpine");
        }

        /** Relógio controlável (fronteira de dia em America/Sao_Paulo); por padrão é o do sistema. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** Mesmo conjunto de validadores de produção; só a origem da chave pública muda. */
        @Bean
        @Primary
        JwtDecoder testJwtDecoder(OidcProperties props) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwt.publicKey()).build();
            decoder.setJwtValidator(JwtValidation.validators(props));
            return decoder;
        }
    }
}
