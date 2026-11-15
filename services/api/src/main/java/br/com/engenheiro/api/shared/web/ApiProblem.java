package br.com.engenheiro.api.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Corpo de erro conforme `contratos/erro.schema.json`. Nunca carrega dados sensíveis. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiProblem(String type, String title, int status, String code, String request_id, String detail,
                         List<FieldError> errors) {

    public record FieldError(String field, String message) {
    }

    public static ApiProblem of(ErrorCode code, String detail, List<FieldError> errors) {
        return new ApiProblem("https://engenheiro.invalid/erros/" + code.name().toLowerCase().replace('_', '-'),
                code.title(), code.status().value(), code.name(), RequestIds.current(), detail, errors);
    }
}
