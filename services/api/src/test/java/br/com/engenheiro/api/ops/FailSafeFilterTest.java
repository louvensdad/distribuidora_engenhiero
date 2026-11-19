package br.com.engenheiro.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.shared.ops.FailSafeFilter;
import br.com.engenheiro.api.shared.security.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** F16: exceção que escapa de um filtro nunca vira página HTML do servidor. */
class FailSafeFilterTest {
    final JsonMapper json = JsonMapper.builder().build();
    final FailSafeFilter filter = new FailSafeFilter(new ProblemWriter(json), json);

    @Test
    void bancoForaNoFiltroVira503Json() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/products"), res, (rq, rs) -> {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection; host db.interno:5432 user=engenheiro");
        });
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentType()).startsWith("application/problem+json");
        assertThat(res.getContentAsString()).contains("INTEGRATION_UNAVAILABLE").contains("Nada foi gravado")
                .doesNotContain("db.interno").doesNotContain("JDBC");
    }

    @Test
    void conexaoPerdidaNoRollbackTambemE503() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), res, (rq, rs) -> {
            throw new org.springframework.transaction.TransactionSystemException("JDBC rollback failed",
                    new java.sql.SQLException("This connection has been closed.", "08003"));
        });
        assertThat(res.getStatus()).isEqualTo(503);
        // Variante real do pool: "Connection is closed" sem SQLState, com a causa original como exceção da aplicação.
        res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), res, (rq, rs) -> {
            var tse = new org.springframework.transaction.TransactionSystemException("JDBC rollback failed",
                    new java.sql.SQLException("Connection is closed"));
            tse.initApplicationException(new org.springframework.jdbc.CannotGetJdbcConnectionException("sem conexão"));
            throw tse;
        });
        assertThat(res.getStatus()).isEqualTo(503);
    }

    @Test
    void outraExcecaoVira500GenericoSemDetalhe() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), res, (rq, rs) -> {
            throw new IllegalStateException("cpf 52998224725 vazou");
        });
        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("52998224725").doesNotContain("vazou");
    }
}
