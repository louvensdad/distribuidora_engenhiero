package br.com.engenheiro.api.shared.web;

import java.util.List;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final List<ApiProblem.FieldError> errors;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public ApiException(ErrorCode code, String detail, List<ApiProblem.FieldError> errors) {
        super(detail);
        this.code = code;
        this.errors = errors;
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiProblem.FieldError> errors() {
        return errors;
    }

    public static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "Você não tem permissão para esta operação.");
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Registro não encontrado.");
    }
}
