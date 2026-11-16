package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy;
import br.com.engenheiro.api.identityaccess.domain.AccessPolicy.Decision;
import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.identityaccess.domain.CapabilityCatalog;
import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RecordRef;
import br.com.engenheiro.api.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Ponto único de autorização do servidor. Todo controller/serviço de domínio chama aqui; menus escondidos
 * no cliente não substituem esta verificação.
 */
@Service
public class AuthorizationService {
    private final CapabilityCatalog catalog;
    private final AuditService audit;
    private final Clock clock;

    public AuthorizationService(CapabilityCatalog catalog, AuditService audit, Clock clock) {
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    public Decision decide(Actor actor, String resource, String action) {
        return AccessPolicy.decide(catalog, actor, resource, action, clock.instant());
    }

    /** Exige capacidade; senão 403 e auditoria da tentativa. */
    public Decision require(Actor actor, String resource, String action) {
        Decision d = decide(actor, resource, action);
        if (!d.allowed()) {
            denied(actor, resource + "." + action, null, null, "capacidade ausente");
            throw ApiException.forbidden();
        }
        return d;
    }

    /**
     * Exige capacidade E escopo sobre o registro. Registro fora do escopo responde 404 para não revelar
     * existência (ID conhecido não amplia acesso).
     */
    public void requireRecord(Actor actor, String resource, String action, String entityType, String entityId,
                              RecordRef record) {
        Decision d = require(actor, resource, action);
        if (!AccessPolicy.recordInScope(actor, d.scopes(), record)) {
            denied(actor, resource + "." + action, entityType, entityId, "registro fora do escopo");
            throw ApiException.notFound();
        }
    }

    /** Alçada monetária (desconto, estorno, aprovação). Valor exato em BigDecimal. */
    public void requireAmountWithinLimit(Actor actor, String resource, String action, BigDecimal amount) {
        Decision d = require(actor, resource, action);
        if (d.maxAmount().isPresent() && amount.compareTo(d.maxAmount().get()) > 0) {
            denied(actor, resource + "." + action, null, null, "valor acima da alçada");
            throw ApiException.forbidden();
        }
    }

    /**
     * Funções sem recurso próprio no contrato de permissões (ADR-017): só administrador, auditando a negação.
     */
    public void requireAdmin(Actor actor, String capability) {
        if (!actor.admin()) {
            denied(actor, capability, null, null, "exige administrador (sem recurso no contrato)");
            throw ApiException.forbidden();
        }
    }

    public boolean canSeeField(Actor actor, FieldClass field) {
        return AccessPolicy.canSeeField(actor, field);
    }

    public CapabilityCatalog catalog() {
        return catalog;
    }

    private void denied(Actor actor, String capability, String entityType, String entityId, String reason) {
        audit.recordIndependently(new AuditEntry(actor.organizationId(), actor.branchId(), actor.userId(), "USER",
                actor.clientId(), "access.denied", entityType, entityId, "DENIED", reason,
                Map.of("capability", capability), "api"));
    }
}
