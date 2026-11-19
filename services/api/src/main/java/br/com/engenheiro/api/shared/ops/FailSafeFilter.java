package br.com.engenheiro.api.shared.ops;

import br.com.engenheiro.api.shared.security.ProblemWriter;
import br.com.engenheiro.api.shared.web.ErrorCode;
import br.com.engenheiro.api.shared.web.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rede de proteção (F16): exceção que escapa dos filtros (ex.: banco fora ao identificar o ator, antes de qualquer
 * controller) vira problema JSON — 503 quando é o banco, 500 genérico no resto —, nunca a página HTML do servidor com
 * detalhes. Fica dentro do log de acesso (o status registrado é o devolvido).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class FailSafeFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(FailSafeFilter.class);
    private final ProblemWriter problems;
    private final tools.jackson.databind.json.JsonMapper json;

    public FailSafeFilter(ProblemWriter problems, tools.jackson.databind.json.JsonMapper json) {
        this.problems = problems;
        this.json = json;
    }

    /** Banco fora ou conexão perdida em qualquer ponto (obter, usar, confirmar ou desfazer a transação). */
    public static boolean databaseDown(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof DataAccessResourceFailureException || c instanceof CannotCreateTransactionException
                    || c instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            if (c instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("57P"))) {
                return true; // 08xxx: conexão; 57Pxx: banco desligando/reiniciando
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            if (databaseDown(e)) {
                log.warn("Banco indisponível request_id={}", RequestIds.current());
                problems.write(response, ErrorCode.INTEGRATION_UNAVAILABLE,
                        "Serviço temporariamente indisponível (banco de dados). Nada foi gravado; tente novamente.");
            } else {
                log.error("Erro inesperado fora do controller request_id={}", RequestIds.current(), LogSanitizer.scrub(e));
                response.setStatus(500);
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                json.writeValue(response.getOutputStream(), new br.com.engenheiro.api.shared.web.ApiProblem("about:blank",
                        "Erro interno", 500, "INTERNAL_ERROR", RequestIds.current(),
                        "Erro inesperado. Informe o request_id ao suporte.", java.util.List.of()));
            }
        }
    }
}
