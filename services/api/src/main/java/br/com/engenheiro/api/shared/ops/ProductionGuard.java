package br.com.engenheiro.api.shared.ops;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Guarda de produção (F16, OPS-01/OPS-05). Nos perfis {@code prod} e {@code homologacao} a migração do banco e a
 * subida da API são RECUSADAS quando falta parâmetro essencial ou quando há qualquer vestígio de ambiente local
 * (seed fictício, perfil local, HTTP, localhost). Nunca sobe em "modo demo" silencioso.
 */
@Configuration
public class ProductionGuard {
    static final Set<String> GUARDED = Set.of("prod", "homologacao");

    public static boolean guarded(Environment env) {
        return Arrays.stream(env.getActiveProfiles()).anyMatch(GUARDED::contains);
    }

    /** Problemas encontrados (vazio = pode subir). Nunca inclui o valor de um segredo, só o nome do parâmetro. */
    public static List<String> problems(Environment env) {
        List<String> p = new ArrayList<>();
        if (!guarded(env)) {
            return p;
        }
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        if (profiles.contains("local")) {
            p.add("perfil 'local' ativo junto com produção/homologação");
        }
        if (env.getProperty("engenheiro.seed-local.enabled", Boolean.class, false)) {
            p.add("engenheiro.seed-local.enabled=true (dados fictícios)");
        }
        String locations = env.getProperty("spring.flyway.locations", "");
        if (locations.contains("seed-local")) {
            p.add("spring.flyway.locations inclui db/seed-local (dados fictícios)");
        }
        if (blank(env.getProperty("spring.datasource.password"))) {
            p.add("ENGENHEIRO_DB_PASSWORD ausente");
        }
        String db = env.getProperty("spring.datasource.url", "");
        if (db.contains("localhost:5433") && profiles.contains("prod")) {
            p.add("ENGENHEIRO_DB_URL ainda aponta para o banco local");
        }
        httpsNotLocal(p, "ENGENHEIRO_OIDC_ISSUER", env.getProperty("engenheiro.security.oidc.issuer"));
        httpsNotLocal(p, "ENGENHEIRO_OIDC_JWKS", env.getProperty("engenheiro.security.oidc.jwk-set-uri"));
        httpsNotLocal(p, "ENGENHEIRO_WEB_APP_URL", env.getProperty("engenheiro.web.app-url"));
        if (blank(env.getProperty("engenheiro.web.client-secret"))) {
            p.add("ENGENHEIRO_WEB_CLIENT_SECRET ausente (login do site)");
        }
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) {
            p.add("cookie de sessão sem Secure (ENGENHEIRO_COOKIE_SECURE=false)");
        }
        if (blank(env.getProperty("engenheiro.storage.local-dir"))) {
            p.add("ENGENHEIRO_STORAGE_DIR ausente (documentos e anexos)");
        }
        String pepper = env.getProperty("engenheiro.log.pepper", "");
        if (pepper.length() < 32) {
            p.add("ENGENHEIRO_LOG_PEPPER ausente ou curto (mínimo 32 caracteres) para pseudonimizar o ator nos logs");
        }
        return p;
    }

    private static void httpsNotLocal(List<String> p, String name, String value) {
        if (blank(value)) {
            p.add(name + " ausente");
            return;
        }
        try {
            URI u = URI.create(value.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(u.getScheme())) {
                p.add(name + " sem HTTPS");
            }
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("10.0.2.2")) {
                p.add(name + " aponta para endereço local");
            }
        } catch (IllegalArgumentException e) {
            p.add(name + " inválido");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public static void check(Environment env) {
        List<String> p = problems(env);
        if (!p.isEmpty()) {
            throw new IllegalStateException("Configuração de " + String.join("/", env.getActiveProfiles())
                    + " recusada — nada foi migrado. Corrija: " + String.join("; ", p));
        }
    }

    /** A migração só roda depois da guarda: parâmetro faltando = nenhum DDL aplicado. */
    @Bean
    FlywayMigrationStrategy guardedMigration(Environment env) {
        return (Flyway flyway) -> {
            check(env);
            flyway.migrate();
        };
    }
}
