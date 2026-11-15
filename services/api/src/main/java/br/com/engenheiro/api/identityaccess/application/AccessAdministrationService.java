package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.AccessPolicyCommand.FieldSpec;
import br.com.engenheiro.api.identityaccess.application.AccessPolicyCommand.GrantSpec;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.AppClients;
import br.com.engenheiro.api.identityaccess.domain.Effect;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.Grant;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository.MembershipRow;
import br.com.engenheiro.api.shared.ids.Ids;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessAdministrationService {
    private final IdentityRepository repo;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final Clock clock;

    public AccessAdministrationService(IdentityRepository repo, AuthorizationService authz, AuditService audit,
                                       Clock clock) {
        this.repo = repo;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    public record PolicyView(MembershipRow membership, Set<String> clients, List<Grant> grants,
                             Map<FieldClass, Effect> fields) {
    }

    @Transactional(readOnly = true)
    public List<MembershipRow> listUsers(Actor actor) {
        authz.require(actor, "users", "view");
        return repo.listMemberships(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public PolicyView getPolicy(Actor actor, UUID targetUserId) {
        authz.require(actor, "permissions", "view");
        MembershipRow m = repo.findMembership(actor.organizationId(), targetUserId)
                .orElseThrow(ApiException::notFound);
        return new PolicyView(m, repo.clientsOf(m.membershipId()), repo.activeGrants(m.membershipId()),
                repo.activeFieldPolicies(m.membershipId()));
    }

    @Transactional
    public PolicyView replacePolicy(Actor actor, UUID targetUserId, long expectedVersion, AccessPolicyCommand cmd) {
        authz.require(actor, "permissions", "edit_draft");
        if (actor.userId().equals(targetUserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Ninguém altera o próprio acesso.");
        }
        requireReason(cmd.reason());
        repo.lockAdminMemberships(actor.organizationId());
        MembershipRow target = repo.lockMembership(actor.organizationId(), targetUserId)
                .orElseThrow(ApiException::notFound);
        if (target.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT,
                    "O acesso foi alterado por outra pessoa. Recarregue e revise.");
        }

        if (target.admin() && !actor.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Somente administrador altera outro administrador.");
        }
        RoleTemplate template = parseTemplate(cmd.template());
        boolean makeAdmin = template != null ? template.admin() : target.admin();
        if (makeAdmin && !actor.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Somente administrador concede acesso de administrador.");
        }
        if (target.admin() && !makeAdmin && "ACTIVE".equals(target.status())
                && repo.countActiveAdmins(actor.organizationId()) <= 1) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Não é possível remover o último administrador.");
        }

        List<GrantSpec> grants = cmd.grants() != null ? cmd.grants() : templateGrants(template);
        List<FieldSpec> fields = cmd.fields() != null ? cmd.fields() : templateFields(template);
        Set<String> clients = cmd.clients() != null ? cmd.clients()
                : template != null ? template.clients() : repo.clientsOf(target.membershipId());
        validate(actor, grants, fields, clients);

        PolicyView before = new PolicyView(target, repo.clientsOf(target.membershipId()),
                repo.activeGrants(target.membershipId()), repo.activeFieldPolicies(target.membershipId()));

        repo.revokeAllGrants(target.membershipId(), actor.userId());
        for (GrantSpec g : grants) {
            repo.insertGrant(Ids.newId(), target.membershipId(), g.resource(), g.action(), g.scope(), g.effect(),
                    g.maxAmount(), g.expiresAt(), actor.userId());
        }
        for (FieldSpec f : fields) {
            repo.insertFieldPolicy(Ids.newId(), target.membershipId(), FieldClass.fromKey(f.field()), f.effect(),
                    actor.userId());
        }
        repo.replaceClients(target.membershipId(), clients);
        if (!repo.bumpVersion(target.membershipId(), expectedVersion, actor.userId(),
                template != null ? template.name() : null, makeAdmin, null)) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "Versão desatualizada.");
        }

        PolicyView after = getPolicyUnchecked(actor.organizationId(), targetUserId);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("before", summary(before));
        changes.put("after", summary(after));
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "permissions.replace", "membership", target.membershipId().toString(),
                "SUCCESS", cmd.reason(), changes, "api"));
        return after;
    }

    @Transactional
    public MembershipRow setStatus(Actor actor, UUID targetUserId, long expectedVersion, String status,
                                   String reason) {
        authz.require(actor, "users", "edit_draft");
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Situação inválida.",
                    List.of(new FieldError("status", "Use ACTIVE ou DISABLED")));
        }
        if (actor.userId().equals(targetUserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Ninguém altera a própria situação.");
        }
        requireReason(reason);
        repo.lockAdminMemberships(actor.organizationId());
        MembershipRow target = repo.lockMembership(actor.organizationId(), targetUserId)
                .orElseThrow(ApiException::notFound);
        if (target.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "Versão desatualizada.");
        }
        if (target.admin() && !actor.admin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Somente administrador altera outro administrador.");
        }
        if ("DISABLED".equals(status) && target.admin() && "ACTIVE".equals(target.status())
                && repo.countActiveAdmins(actor.organizationId()) <= 1) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Não é possível bloquear o último administrador.");
        }
        repo.bumpVersion(target.membershipId(), expectedVersion, actor.userId(), null, null, status);
        audit.record(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "membership.status", "membership", target.membershipId().toString(), "SUCCESS",
                reason, Map.of("before", target.status(), "after", status), "api"));
        return repo.findMembership(actor.organizationId(), targetUserId).orElseThrow();
    }

    /** Usado pelo seed local (identidade de serviço), nunca exposto por HTTP. */
    @Transactional
    public void applyTemplateAsService(UUID membershipId, RoleTemplate template, String serviceName) {
        for (RoleTemplate.TemplateGrant g : template.grants()) {
            repo.insertGrant(Ids.newId(), membershipId, g.resource(), g.action(), g.scope(), Effect.ALLOW, null,
                    null, null);
        }
        template.fields().forEach((f, e) -> repo.insertFieldPolicy(Ids.newId(), membershipId, f, e, null));
        audit.record(new AuditEntry(null, null, null, "SERVICE", serviceName, "permissions.template_applied",
                "membership", membershipId.toString(), "SUCCESS", "seed de desenvolvimento",
                Map.of("template", template.name()), "seed"));
    }

    private PolicyView getPolicyUnchecked(UUID org, UUID userId) {
        MembershipRow m = repo.findMembership(org, userId).orElseThrow();
        return new PolicyView(m, repo.clientsOf(m.membershipId()), repo.activeGrants(m.membershipId()),
                repo.activeFieldPolicies(m.membershipId()));
    }

    private void validate(Actor actor, List<GrantSpec> grants, List<FieldSpec> fields, Set<String> clients) {
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < grants.size(); i++) {
            GrantSpec g = grants.get(i);
            String path = "grants[" + i + "]";
            if (g.resource() == null || g.action() == null || g.scope() == null || g.effect() == null) {
                errors.add(new FieldError(path, "resource, action, scope e effect são obrigatórios"));
                continue;
            }
            if (!authz.catalog().contains(g.resource(), g.action())) {
                errors.add(new FieldError(path, "Capacidade inexistente: " + g.resource() + "." + g.action()));
                continue;
            }
            if (g.maxAmount() != null && g.maxAmount().signum() < 0) {
                errors.add(new FieldError(path + ".maxAmount", "Limite não pode ser negativo"));
            }
            if (g.maxAmount() != null && g.maxAmount().scale() > 2) {
                errors.add(new FieldError(path + ".maxAmount", "Use no máximo duas casas decimais"));
            }
            if (g.expiresAt() != null && !g.expiresAt().isAfter(clock.instant())) {
                errors.add(new FieldError(path + ".expiresAt", "Validade precisa estar no futuro"));
            }
            // Não concede o que não possui (administrador possui tudo, exceto negações explícitas).
            if (g.effect() == Effect.ALLOW && !authz.decide(actor, g.resource(), g.action()).allowed()) {
                errors.add(new FieldError(path, "Você não pode conceder uma capacidade que não possui"));
            }
        }
        for (int i = 0; i < fields.size(); i++) {
            FieldSpec f = fields.get(i);
            try {
                FieldClass fc = FieldClass.fromKey(f.field());
                if (f.effect() == Effect.ALLOW && !AccessPolicy.canSeeField(actor, fc)) {
                    errors.add(new FieldError("fields[" + i + "]", "Você não pode liberar um campo que não vê"));
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                errors.add(new FieldError("fields[" + i + "]", "Classe de campo inexistente"));
            }
        }
        for (String c : clients) {
            if (!AppClients.ALL.contains(c)) {
                errors.add(new FieldError("clients", "Aplicativo desconhecido: " + c));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Política de acesso inválida.", errors);
        }
    }

    private static RoleTemplate parseTemplate(String name) {
        if (name == null) {
            return null;
        }
        try {
            return RoleTemplate.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Modelo inexistente.",
                    List.of(new FieldError("template", "Modelo inexistente: " + name)));
        }
    }

    private static List<GrantSpec> templateGrants(RoleTemplate t) {
        if (t == null) {
            return List.of();
        }
        return t.grants().stream()
                .map(g -> new GrantSpec(g.resource(), g.action(), g.scope(), Effect.ALLOW, null, null)).toList();
    }

    private static List<FieldSpec> templateFields(RoleTemplate t) {
        if (t == null) {
            return List.of();
        }
        return t.fields().entrySet().stream().map(e -> new FieldSpec(e.getKey().key(), e.getValue())).toList();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Informe o motivo.",
                    List.of(new FieldError("reason", "Motivo obrigatório")));
        }
    }

    private static Map<String, Object> summary(PolicyView v) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("template", v.membership().roleTemplate());
        s.put("admin", v.membership().admin());
        s.put("clients", v.clients().stream().sorted().toList());
        s.put("grants", v.grants().stream()
                .map(g -> g.effect() + " " + g.resource() + "." + g.action() + "@" + g.scope()
                        + (g.maxAmount() != null ? " max=" + g.maxAmount().toPlainString() : "")
                        + (g.expiresAt() != null ? " até=" + g.expiresAt() : ""))
                .toList());
        Map<String, String> f = new LinkedHashMap<>();
        v.fields().forEach((k, e) -> f.put(k.key(), e.name()));
        s.put("fields", f);
        return s;
    }
}
