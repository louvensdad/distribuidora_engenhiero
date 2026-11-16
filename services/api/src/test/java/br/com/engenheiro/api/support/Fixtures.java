package br.com.engenheiro.api.support;

import br.com.engenheiro.api.identityaccess.application.AccessAdministrationService;
import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.shared.ids.Ids;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Cria organizações/pessoas fictícias isoladas por teste. */
@Component
public class Fixtures {
    private final JdbcClient jdbc;
    private final AccessAdministrationService admin;

    public Fixtures(JdbcClient jdbc, AccessAdministrationService admin) {
        this.jdbc = jdbc;
        this.admin = admin;
    }

    public record Org(UUID id, UUID branch1, UUID branch2) {
    }

    public record Person(UUID userId, UUID membershipId, String subject, String client) {
        public String token() {
            return TestJwt.token(subject, client);
        }

        public String tokenFor(String otherClient) {
            return TestJwt.token(subject, otherClient);
        }
    }

    public Org org() {
        UUID org = Ids.newId();
        UUID b1 = Ids.newId();
        UUID b2 = Ids.newId();
        jdbc.sql("INSERT INTO organizations (id, name, status) VALUES (:id, 'Distribuidora de Cana Engenheiro Ltda', 'ACTIVE')")
                .param("id", org).update();
        jdbc.sql("INSERT INTO branches (id, organization_id, name, status) VALUES (:id, :org, :n, 'ACTIVE')")
                .param("id", b1).param("org", org).param("n", "Matriz").update();
        jdbc.sql("INSERT INTO branches (id, organization_id, name, status) VALUES (:id, :org, :n, 'ACTIVE')")
                .param("id", b2).param("org", org).param("n", "Unidade 2").update();
        return new Org(org, b1, b2);
    }

    public Person person(Org org, UUID branch, RoleTemplate template, String name) {
        UUID user = Ids.newId();
        UUID membership = Ids.newId();
        String subject = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO app_users (id, issuer, external_subject, display_name, status) "
                        + "VALUES (:id, :iss, :sub, :n, 'ACTIVE')")
                .param("id", user).param("iss", TestJwt.ISSUER).param("sub", subject).param("n", name).update();
        jdbc.sql("""
                        INSERT INTO memberships (id, user_id, organization_id, branch_id, sector, role_template,
                                                 is_admin, status)
                        VALUES (:id, :u, :org, :b, :s, :t, :adm, 'ACTIVE')
                        """)
                .param("id", membership).param("u", user).param("org", org.id()).param("b", branch)
                .param("s", template.sector().name()).param("t", template.name()).param("adm", template.admin())
                .update();
        Set<String> clients = template.clients();
        for (String c : clients) {
            jdbc.sql("INSERT INTO membership_clients (membership_id, client_id) VALUES (:m, :c)")
                    .param("m", membership).param("c", c).update();
        }
        if (!template.admin()) {
            admin.applyTemplateAsService(membership, template, "teste");
        }
        String primaryClient = template.admin() ? AppClients.WEB : clients.iterator().next();
        return new Person(user, membership, subject, primaryClient);
    }

    /** Concessão extra (o que o dono liberaria no mapa de acessos). maxAmount = alçada; null = sem valor. */
    public void grant(Person p, String resource, String action, String scope, java.math.BigDecimal maxAmount) {
        jdbc.sql("""
                        INSERT INTO permission_grants (id, membership_id, resource, action, scope, effect, max_amount)
                        VALUES (:id, :m, :r, :a, :s, 'ALLOW', :max)
                        """)
                .param("id", Ids.newId()).param("m", p.membershipId()).param("r", resource).param("a", action)
                .param("s", scope).param("max", maxAmount).update();
    }
}
