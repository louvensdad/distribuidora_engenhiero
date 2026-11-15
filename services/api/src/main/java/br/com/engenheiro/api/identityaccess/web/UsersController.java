package br.com.engenheiro.api.identityaccess.web;

import br.com.engenheiro.api.identityaccess.application.AccessAdministrationService;
import br.com.engenheiro.api.identityaccess.application.AccessAdministrationService.PolicyView;
import br.com.engenheiro.api.identityaccess.application.AccessPolicyCommand;
import br.com.engenheiro.api.identityaccess.application.AccessPolicyCommand.FieldSpec;
import br.com.engenheiro.api.identityaccess.application.AccessPolicyCommand.GrantSpec;
import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.Effect;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import br.com.engenheiro.api.identityaccess.persistence.IdentityRepository.MembershipRow;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ApiProblem.FieldError;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class UsersController {
    private final AccessAdministrationService service;
    private final AuthorizationService authz;

    public UsersController(AccessAdministrationService service, AuthorizationService authz) {
        this.service = service;
        this.authz = authz;
    }

    public record UserView(UUID id, String display_name, String status, String sector, String role_template,
                           boolean admin, String branch, long version) {
        static UserView of(MembershipRow m) {
            String status = "ACTIVE".equals(m.userStatus()) ? m.status() : m.userStatus();
            return new UserView(m.userId(), m.displayName(), status, m.sector().name(), m.roleTemplate(),
                    m.admin(), m.branchName(), m.version());
        }
    }

    public record GrantDto(String resource, String action, Scope scope, Effect effect, String max_amount,
                           Instant expires_at) {
    }

    public record FieldDto(String field, Effect effect) {
    }

    public record PolicyDto(UserView user, List<String> clients, List<GrantDto> grants, List<FieldDto> fields) {
    }

    public record PolicyRequest(String template, List<GrantDto> grants, List<FieldDto> fields, Set<String> clients,
                                String reason) {
    }

    public record StatusRequest(String status, String reason) {
    }

    public record Page<T>(List<T> items, String next_cursor) {
    }

    @GetMapping("/users")
    public Page<UserView> list(Actor actor) {
        return new Page<>(service.listUsers(actor).stream().map(UserView::of).toList(), null);
    }

    @GetMapping("/users/{id}/access-policy")
    public ResponseEntity<PolicyDto> policy(Actor actor, @PathVariable UUID id) {
        PolicyView v = service.getPolicy(actor, id);
        return withEtag(v);
    }

    @PostMapping("/users/{id}/access-policy")
    public ResponseEntity<PolicyDto> replace(Actor actor, @PathVariable UUID id,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @RequestBody PolicyRequest body) {
        long version = parseVersion(ifMatch);
        AccessPolicyCommand cmd = new AccessPolicyCommand(body.template(),
                body.grants() == null ? null : body.grants().stream().map(UsersController::toSpec).toList(),
                body.fields() == null ? null : body.fields().stream()
                        .map(f -> new FieldSpec(f.field(), f.effect())).toList(),
                body.clients(), body.reason());
        return withEtag(service.replacePolicy(actor, id, version, cmd));
    }

    @PostMapping("/users/{id}/status")
    public ResponseEntity<UserView> status(Actor actor, @PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @RequestBody StatusRequest body) {
        MembershipRow m = service.setStatus(actor, id, parseVersion(ifMatch), body.status(), body.reason());
        return ResponseEntity.ok().eTag("\"" + m.version() + "\"").body(UserView.of(m));
    }

    @GetMapping("/role-templates")
    public List<Map<String, Object>> templates(Actor actor) {
        authz.require(actor, "permissions", "view");
        return Arrays.stream(RoleTemplate.values()).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("sector", t.sector().name());
            m.put("admin", t.admin());
            m.put("clients", t.clients().stream().sorted().toList());
            m.put("grants", t.grants().stream().map(g -> g.resource() + "." + g.action() + "@" + g.scope()).toList());
            Map<String, String> f = new LinkedHashMap<>();
            t.fields().forEach((k, e) -> f.put(k.key(), e.name()));
            m.put("fields", f);
            return m;
        }).toList();
    }

    private static ResponseEntity<PolicyDto> withEtag(PolicyView v) {
        PolicyDto dto = new PolicyDto(UserView.of(v.membership()), v.clients().stream().sorted().toList(),
                v.grants().stream().map(g -> new GrantDto(g.resource(), g.action(), g.scope(), g.effect(),
                        g.maxAmount() == null ? null : g.maxAmount().toPlainString(), g.expiresAt())).toList(),
                v.fields().entrySet().stream().map(e -> new FieldDto(e.getKey().key(), e.getValue())).toList());
        return ResponseEntity.ok().eTag("\"" + v.membership().version() + "\"")
                .cacheControl(CacheControl.noStore()).body(dto);
    }

    private static GrantSpec toSpec(GrantDto g) {
        BigDecimal max = null;
        if (g.max_amount() != null) {
            try {
                max = new BigDecimal(g.max_amount());
            } catch (NumberFormatException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Valor inválido.",
                        List.of(new FieldError("max_amount", "Use decimal em texto, ex.: \"150.00\"")));
            }
        }
        return new GrantSpec(g.resource(), g.action(), g.scope(), g.effect(), max, g.expires_at());
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Cabeçalho If-Match obrigatório.",
                    List.of(new FieldError("If-Match", "Informe a versão (ETag) lida")));
        }
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "If-Match inválido.");
        }
    }
}
