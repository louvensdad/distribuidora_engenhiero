package br.com.engenheiro.api.identityaccess.persistence;

import br.com.engenheiro.api.identityaccess.domain.Effect;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.Grant;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import br.com.engenheiro.api.identityaccess.domain.Sector;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
    private final JdbcClient jdbc;

    public IdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record MembershipRow(UUID membershipId, UUID userId, String displayName, String userStatus,
                                UUID organizationId, String organizationName, String organizationStatus,
                                UUID branchId, String branchName, Sector sector, String roleTemplate,
                                boolean admin, String status, long version) {
        public boolean fullyActive() {
            return "ACTIVE".equals(userStatus) && "ACTIVE".equals(status) && "ACTIVE".equals(organizationStatus);
        }
    }

    private static final String MEMBERSHIP_SELECT = """
            SELECT m.id AS membership_id, u.id AS user_id, u.display_name, u.status AS user_status,
                   o.id AS organization_id, o.name AS organization_name, o.status AS organization_status,
                   b.id AS branch_id, b.name AS branch_name, m.sector, m.role_template, m.is_admin,
                   m.status, m.version
              FROM memberships m
              JOIN app_users u ON u.id = m.user_id
              JOIN organizations o ON o.id = m.organization_id
              JOIN branches b ON b.id = m.branch_id
            """;

    public List<MembershipRow> findMembershipsBySubject(String issuer, String subject) {
        return jdbc.sql(MEMBERSHIP_SELECT + " WHERE u.issuer = :iss AND u.external_subject = :sub")
                .param("iss", issuer).param("sub", subject)
                .query(IdentityRepository::mapMembership).list();
    }

    public Optional<MembershipRow> findMembership(UUID organizationId, UUID userId) {
        return jdbc.sql(MEMBERSHIP_SELECT + " WHERE m.organization_id = :org AND m.user_id = :user")
                .param("org", organizationId).param("user", userId)
                .query(IdentityRepository::mapMembership).optional();
    }

    /** Bloqueia o vínculo para alteração concorrente de política. */
    public Optional<MembershipRow> lockMembership(UUID organizationId, UUID userId) {
        return jdbc.sql(MEMBERSHIP_SELECT + " WHERE m.organization_id = :org AND m.user_id = :user FOR UPDATE OF m")
                .param("org", organizationId).param("user", userId)
                .query(IdentityRepository::mapMembership).optional();
    }

    public List<MembershipRow> listMemberships(UUID organizationId) {
        return jdbc.sql(MEMBERSHIP_SELECT + " WHERE m.organization_id = :org ORDER BY u.display_name, u.id")
                .param("org", organizationId)
                .query(IdentityRepository::mapMembership).list();
    }

    public Set<String> clientsOf(UUID membershipId) {
        return jdbc.sql("SELECT client_id FROM membership_clients WHERE membership_id = :m")
                .param("m", membershipId).query(String.class).set();
    }

    public List<Grant> activeGrants(UUID membershipId) {
        return jdbc.sql("""
                        SELECT id, resource, action, scope, effect, max_amount, expires_at
                          FROM permission_grants
                         WHERE membership_id = :m AND revoked_at IS NULL
                         ORDER BY resource, action, scope
                        """)
                .param("m", membershipId)
                .query((rs, n) -> new Grant(rs.getObject("id", UUID.class), rs.getString("resource"),
                        rs.getString("action"), Scope.valueOf(rs.getString("scope")),
                        Effect.valueOf(rs.getString("effect")), rs.getBigDecimal("max_amount"),
                        instant(rs, "expires_at")))
                .list();
    }

    public Map<FieldClass, Effect> activeFieldPolicies(UUID membershipId) {
        Map<FieldClass, Effect> map = new EnumMap<>(FieldClass.class);
        jdbc.sql("SELECT field_class, effect FROM field_policies WHERE membership_id = :m AND revoked_at IS NULL")
                .param("m", membershipId)
                .query((rs, n) -> Map.entry(FieldClass.fromKey(rs.getString("field_class")),
                        Effect.valueOf(rs.getString("effect"))))
                .list()
                .forEach(e -> map.merge(e.getKey(), e.getValue(),
                        (a, b) -> a == Effect.DENY || b == Effect.DENY ? Effect.DENY : Effect.ALLOW));
        return map;
    }

    /**
     * Trava (em ordem de id) os vínculos de administrador da organização. Chamado no início de toda mudança de
     * acesso/situação para serializar alterações concorrentes e nunca zerar os administradores ativos.
     */
    public void lockAdminMemberships(UUID organizationId) {
        jdbc.sql("SELECT id FROM memberships WHERE organization_id = :org AND is_admin ORDER BY id FOR UPDATE")
                .param("org", organizationId).query(UUID.class).list();
    }

    public int countActiveAdmins(UUID organizationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM memberships m JOIN app_users u ON u.id = m.user_id
                         WHERE m.organization_id = :org AND m.is_admin AND m.status = 'ACTIVE'
                           AND u.status = 'ACTIVE'
                        """)
                .param("org", organizationId).query(Integer.class).single();
    }

    public void revokeAllGrants(UUID membershipId, UUID by) {
        jdbc.sql("UPDATE permission_grants SET revoked_at = now(), revoked_by = :by "
                        + "WHERE membership_id = :m AND revoked_at IS NULL")
                .param("by", by).param("m", membershipId).update();
        jdbc.sql("UPDATE field_policies SET revoked_at = now(), revoked_by = :by "
                        + "WHERE membership_id = :m AND revoked_at IS NULL")
                .param("by", by).param("m", membershipId).update();
    }

    public void insertGrant(UUID id, UUID membershipId, String resource, String action, Scope scope, Effect effect,
                            BigDecimal maxAmount, Instant expiresAt, UUID by) {
        jdbc.sql("""
                        INSERT INTO permission_grants
                            (id, membership_id, resource, action, scope, effect, max_amount, expires_at, created_by)
                        VALUES (:id, :m, :r, :a, :s, :e, :max, :exp, :by)
                        """)
                .param("id", id).param("m", membershipId).param("r", resource).param("a", action)
                .param("s", scope.name()).param("e", effect.name()).param("max", maxAmount)
                .param("exp", expiresAt == null ? null : Timestamp.from(expiresAt)).param("by", by)
                .update();
    }

    public void insertFieldPolicy(UUID id, UUID membershipId, FieldClass field, Effect effect, UUID by) {
        jdbc.sql("INSERT INTO field_policies (id, membership_id, field_class, effect, created_by) "
                        + "VALUES (:id, :m, :f, :e, :by)")
                .param("id", id).param("m", membershipId).param("f", field.key()).param("e", effect.name())
                .param("by", by).update();
    }

    public void replaceClients(UUID membershipId, Set<String> clients) {
        jdbc.sql("DELETE FROM membership_clients WHERE membership_id = :m").param("m", membershipId).update();
        for (String c : clients) {
            jdbc.sql("INSERT INTO membership_clients (membership_id, client_id) VALUES (:m, :c)")
                    .param("m", membershipId).param("c", c).update();
        }
    }

    /** Incrementa versão com controle otimista. Retorna false se a versão esperada não confere. */
    public boolean bumpVersion(UUID membershipId, long expectedVersion, UUID by, String roleTemplate,
                               Boolean admin, String status) {
        return jdbc.sql("""
                        UPDATE memberships
                           SET version = version + 1, updated_at = now(), updated_by = :by,
                               role_template = COALESCE(:tpl, role_template),
                               is_admin = COALESCE(:adm, is_admin),
                               status = COALESCE(:st, status)
                         WHERE id = :m AND version = :v
                        """)
                .param("by", by).param("tpl", roleTemplate).param("adm", admin).param("st", status)
                .param("m", membershipId).param("v", expectedVersion).update() == 1;
    }

    public void setUserStatus(UUID userId, String status) {
        jdbc.sql("UPDATE app_users SET status = :s WHERE id = :u").param("s", status).param("u", userId).update();
    }

    public Set<UUID> membershipsWithoutPolicy() {
        return jdbc.sql("""
                        SELECT m.id FROM memberships m
                         WHERE NOT m.is_admin
                           AND NOT EXISTS (SELECT 1 FROM permission_grants g WHERE g.membership_id = m.id)
                        """)
                .query(UUID.class).list().stream().collect(Collectors.toSet());
    }

    private static MembershipRow mapMembership(ResultSet rs, int n) throws SQLException {
        return new MembershipRow(rs.getObject("membership_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("display_name"), rs.getString("user_status"),
                rs.getObject("organization_id", UUID.class), rs.getString("organization_name"),
                rs.getString("organization_status"), rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"), Sector.valueOf(rs.getString("sector")), rs.getString("role_template"),
                rs.getBoolean("is_admin"), rs.getString("status"), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
