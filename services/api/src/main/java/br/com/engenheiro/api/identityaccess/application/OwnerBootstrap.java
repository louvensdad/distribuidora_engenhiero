package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.shared.ids.Ids;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Primeiro acesso de um banco vazio (F16, D06): com {@code ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT} (o id da conta do dono
 * no provedor de identidade) e nenhuma empresa cadastrada, cria a empresa, a matriz e o dono como administrador do
 * site e do app Patrão — e mais nada (funcionários, cargos e acessos o dono cria pelo sistema). Já existindo empresa,
 * não faz nada. Sob trava consultiva: várias instâncias subindo juntas criam uma vez só. Auditado como serviço.
 * Depois da primeira subida a variável é retirada (runbook de implantação).
 */
@Component
@ConditionalOnProperty(name = "engenheiro.bootstrap.owner-subject")
public class OwnerBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AccessAdministrationService admin;
    private final AuditService audit;
    private final String subject;
    private final String ownerName;
    private final String issuer;
    private final String company;
    private final String branch;

    public OwnerBootstrap(JdbcClient jdbc, TransactionTemplate tx, AccessAdministrationService admin, AuditService audit,
                          @Value("${engenheiro.bootstrap.owner-subject}") String subject,
                          @Value("${engenheiro.bootstrap.owner-name:Responsável}") String ownerName,
                          @Value("${engenheiro.security.oidc.issuer}") String issuer,
                          @Value("${engenheiro.empresa.nome}") String company,
                          @Value("${engenheiro.bootstrap.branch-name:Matriz}") String branch) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.admin = admin;
        this.audit = audit;
        this.subject = subject == null ? "" : subject.trim();
        this.ownerName = ownerName;
        this.issuer = issuer;
        this.company = company;
        this.branch = branch;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (subject.isEmpty()) {
            return;
        }
        Boolean created = tx.execute(st -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('bootstrap-dono'))").query(Object.class).single();
            long orgs = jdbc.sql("SELECT count(*) FROM organizations").query(Long.class).single();
            if (orgs > 0) {
                return false;
            }
            UUID org = Ids.newId();
            UUID b = Ids.newId();
            UUID user = Ids.newId();
            UUID m = Ids.newId();
            jdbc.sql("INSERT INTO organizations (id, name, status) VALUES (:id, :n, 'ACTIVE')")
                    .param("id", org).param("n", company).update();
            jdbc.sql("INSERT INTO branches (id, organization_id, name, status) VALUES (:id, :o, :n, 'ACTIVE')")
                    .param("id", b).param("o", org).param("n", branch).update();
            jdbc.sql("""
                            INSERT INTO app_users (id, issuer, external_subject, display_name, status)
                            VALUES (:id, :i, :s, :n, 'ACTIVE')
                            """).param("id", user).param("i", issuer).param("s", subject).param("n", ownerName).update();
            jdbc.sql("""
                            INSERT INTO memberships (id, user_id, organization_id, branch_id, sector, role_template, is_admin, status)
                            VALUES (:id, :u, :o, :b, 'ADMINISTRACAO', 'ADMIN', true, 'ACTIVE')
                            """).param("id", m).param("u", user).param("o", org).param("b", b).update();
            for (String client : new String[] {AppClients.WEB, AppClients.GESTAO}) {
                jdbc.sql("INSERT INTO membership_clients (membership_id, client_id) VALUES (:m, :c)")
                        .param("m", m).param("c", client).update();
            }
            admin.applyTemplateAsService(m, RoleTemplate.ADMIN, "bootstrap", "primeiro acesso do dono (banco vazio)",
                    "bootstrap");
            audit.record(new AuditEntry(org, b, null, "SERVICE", "bootstrap", "identity.bootstrap_owner", "membership",
                    m.toString(), "SUCCESS", "primeiro acesso do dono (banco vazio)", Map.of("branch", branch), "bootstrap"));
            return true;
        });
        if (Boolean.TRUE.equals(created)) {
            log.warn("BOOTSTRAP: empresa, matriz e dono administrador criados. Retire ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT.");
        } else {
            log.info("Bootstrap ignorado: o banco já tem empresa cadastrada.");
        }
    }
}
