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
    void outraExcecaoVira500GenericoSemDetalhe() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), res, (rq, rs) -> {
            throw new IllegalStateException("cpf 52998224725 vazou");
        });
        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("52998224725").doesNotContain("vazou");
    }
}
