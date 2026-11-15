package br.com.engenheiro.api.shared.security;

import br.com.engenheiro.api.shared.web.ApiProblem;
import br.com.engenheiro.api.shared.web.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProblemWriter {
    private final JsonMapper json;

    public ProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (code == ErrorCode.UNAUTHENTICATED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), ApiProblem.of(code, detail, List.of()));
    }
}
