package br.com.engenheiro.api.identityaccess.web;

import br.com.engenheiro.api.identityaccess.application.AuthorizationService;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.Capability;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capacidades efetivas para a interface montar menus. É só conveniência: o servidor recalcula em cada chamada.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final AuthorizationService authz;

    public MeController(AuthorizationService authz) {
        this.authz = authz;
    }

    public record CapabilityView(String capability, String resource, String action, List<Scope> scopes,
                                 String max_amount) {
    }

    public record MeView(Map<String, Object> user, Map<String, Object> organization, Map<String, Object> branch,
                         String client_id, boolean admin, String role_template, String sector,
                         long policy_version, List<CapabilityView> capabilities, Map<String, Boolean> fields) {
    }

    @GetMapping("/capabilities")
    public ResponseEntity<MeView> capabilities(Actor actor) {
        List<CapabilityView> caps = new ArrayList<>();
        for (Capability c : authz.catalog().all()) {
            Decision d = authz.decide(actor, c.resource(), c.action());
            if (d.allowed()) {
                caps.add(new CapabilityView(c.key(), c.resource(), c.action(),
                        d.scopes().stream().sorted().toList(),
                        d.maxAmount().map(java.math.BigDecimal::toPlainString).orElse(null)));
            }
        }
        Map<String, Boolean> fields = new LinkedHashMap<>();
        for (FieldClass f : FieldClass.values()) {
            fields.put(f.key(), authz.canSeeField(actor, f));
        }
        MeView view = new MeView(
                Map.of("id", actor.userId(), "display_name", actor.displayName()),
                Map.of("id", actor.organizationId(), "name", actor.organizationName()),
                Map.of("id", actor.branchId(), "name", actor.branchName()),
                actor.clientId(), actor.admin(), actor.roleTemplate(), actor.sector().name(),
                actor.policyVersion(), caps, fields);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
}
