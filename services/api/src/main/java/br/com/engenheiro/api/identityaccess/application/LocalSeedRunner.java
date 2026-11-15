package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Aplica os modelos de perfil aos vínculos fictícios do seed local que ainda não têm política. */
@Component
@ConditionalOnProperty(name = "engenheiro.seed-local.enabled", havingValue = "true")
public class LocalSeedRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalSeedRunner.class);
    private final IdentityRepository repo;
    private final AccessAdministrationService admin;
    private final JdbcClient jdbc;

    public LocalSeedRunner(IdentityRepository repo, AccessAdministrationService admin, JdbcClient jdbc) {
        this.repo = repo;
        this.admin = admin;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (UUID membershipId : repo.membershipsWithoutPolicy()) {
            String template = jdbc.sql("SELECT role_template FROM memberships WHERE id = :m")
                    .param("m", membershipId).query(String.class).single();
            admin.applyTemplateAsService(membershipId, RoleTemplate.valueOf(template), "seed-local");
            log.warn("SEED LOCAL FICTÍCIO: modelo {} aplicado ao vínculo {}", template, membershipId);
        }
    }
}
