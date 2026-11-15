package br.com.engenheiro.api.shared.security;

import br.com.engenheiro.api.audit.AuditEntry;
import br.com.engenheiro.api.audit.AuditService;
import br.com.engenheiro.api.identityaccess.application.ActorResolver;
import br.com.engenheiro.api.identityaccess.application.ActorResolver.Result;
import br.com.engenheiro.api.shared.web.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Após validar o JWT, exige vínculo local ativo e app liberado. Não é @Component: registrado só na cadeia. */
public class ActorFilter extends OncePerRequestFilter {
    public static final String ACTOR_ATTRIBUTE = ActorFilter.class.getName() + ".actor";
    public static final String ORGANIZATION_HEADER = "X-Organization-Id";

    private final ActorResolver resolver;
    private final AuditService audit;
    private final ProblemWriter problems;

    public ActorFilter(ActorResolver resolver, AuditService audit, ProblemWriter problems) {
        this.resolver = resolver;
        this.audit = audit;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Result result = resolver.resolve(token.getToken(), request.getHeader(ORGANIZATION_HEADER));
        switch (result) {
            case Result.Resolved r -> {
                request.setAttribute(ACTOR_ATTRIBUTE, r.actor());
                chain.doFilter(request, response);
            }
            case Result.Rejected rej -> {
                audit.recordIndependently(new AuditEntry(rej.organizationId(), null, rej.userId(),
                        rej.userId() == null ? "ANONYMOUS" : "USER", token.getToken().getClaimAsString("azp"),
                        "access.denied", null, null, "DENIED", rej.reason(),
                        Map.of("path", request.getRequestURI()), "api"));
                problems.write(response, ErrorCode.FORBIDDEN, "Conta sem acesso ativo a este aplicativo.");
            }
        }
    }
}
