package br.com.engenheiro.api.shared.web;

import org.springframework.http.HttpStatus;

/** Códigos de `contratos/erro.schema.json`. */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Autenticação necessária"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Acesso negado"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Registro não encontrado"),
    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_CONTENT, "Dados inválidos"),
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "Estoque insuficiente"),
    VERSION_CONFLICT(HttpStatus.PRECONDITION_FAILED, "Versão desatualizada"),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "Transição de estado inválida"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Chave de idempotência reutilizada com outro conteúdo"),
    INTEGRATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Integração indisponível"),
    INTEGRATION_UNCERTAIN(HttpStatus.ACCEPTED, "Resultado da integração incerto"),
    PERIOD_CLOSED(HttpStatus.CONFLICT, "Período fechado"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
