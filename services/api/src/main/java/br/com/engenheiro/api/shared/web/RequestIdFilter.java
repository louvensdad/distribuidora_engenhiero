package br.com.engenheiro.api.shared.web;

import br.com.engenheiro.api.shared.ids.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlação: aceita X-Request-Id seguro do cliente ou gera um novo; devolve no cabeçalho. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestIds.HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : Ids.newId().toString();
        MDC.put(RequestIds.MDC_KEY, id);
        response.setHeader(RequestIds.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }
}
