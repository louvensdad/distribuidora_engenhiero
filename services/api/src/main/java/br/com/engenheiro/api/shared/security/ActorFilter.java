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
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Após autenticar (bearer do app ou sessão do site), exige vínculo local ativo e app liberado.
 * Na sessão web, o access token guardado no servidor é renovado se preciso e validado com o mesmo decoder da API.
 * Não é @Component: registrado só na cadeia de segurança.
 */
public class ActorFilter extends OncePerRequestFilter {
    public static final String ACTOR_ATTRIBUTE = ActorFilter.class.getName() + ".actor";
    public static final String ORGANIZATION_HEADER = "X-Organization-Id";

    private final ActorResolver resolver;
    private final AuditService audit;
    private final ProblemWriter problems;
    private final JwtDecoder jwtDecoder;
    private final OAuth2AuthorizedClientManager webClients;

    public ActorFilter(ActorResolver resolver, AuditService audit, ProblemWriter problems, JwtDecoder jwtDecoder,
                       OAuth2AuthorizedClientManager webClients) {
        this.resolver = resolver;
        this.audit = audit;
        this.problems = problems;
        this.jwtDecoder = jwtDecoder;
        this.webClients = webClients;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt;
        if (auth instanceof JwtAuthenticationToken token) {
            jwt = token.getToken();
        } else if (auth instanceof OAuth2AuthenticationToken web) {
            jwt = sessionAccessToken(web, request, response);
            if (jwt == null) {
                endSession(request);
                problems.write(response, ErrorCode.UNAUTHENTICATED, "Sessão expirada. Entre novamente.");
                return;
            }
        } else {
            chain.doFilter(request, response);
            return;
        }
        Result result = resolver.resolve(jwt, request.getHeader(ORGANIZATION_HEADER));
        switch (result) {
            case Result.Resolved r -> {
                request.setAttribute(ACTOR_ATTRIBUTE, r.actor());
                chain.doFilter(request, response);
            }
            case Result.Rejected rej -> {
                audit.recordIndependently(new AuditEntry(rej.organizationId(), null, rej.userId(),
                        rej.userId() == null ? "ANONYMOUS" : "USER", jwt.getClaimAsString("azp"),
                        "access.denied", null, null, "DENIED", rej.reason(),
                        Map.of("path", request.getRequestURI()), "api"));
                problems.write(response, ErrorCode.FORBIDDEN, "Conta sem acesso ativo a este aplicativo.");
            }
        }
    }

    private Jwt sessionAccessToken(OAuth2AuthenticationToken web, HttpServletRequest request,
                                   HttpServletResponse response) {
        if (webClients == null) {
            return null;
        }
        try {
            OAuth2AuthorizedClient client = webClients.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(web.getAuthorizedClientRegistrationId())
                    .principal(web)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
            return client == null ? null : jwtDecoder.decode(client.getAccessToken().getTokenValue());
        } catch (JwtException | org.springframework.security.oauth2.core.OAuth2AuthorizationException e) {
            return null;
        }
    }

    private static void endSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
