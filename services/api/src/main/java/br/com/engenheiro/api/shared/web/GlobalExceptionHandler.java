package br.com.engenheiro.api.shared.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiProblem> api(ApiException e) {
        return respond(e.code(), e.getMessage(), e.errors());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiProblem> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Arquivo acima de 10 MB.",
                List.of(new ApiProblem.FieldError("file", "Envie um arquivo de até 10 MB")));
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    ResponseEntity<ApiProblem> badMultipart(Exception e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Envie o arquivo no campo 'file' (multipart/form-data).",
                List.of(new ApiProblem.FieldError("file", "Arquivo obrigatório")));
    }

    /** Envio sem arquivo real (ex.: só o nome em JSON) ou campo de formulário ausente: recusa clara, não erro 500. */
    @ExceptionHandler({org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ApiProblem> wrongBody(Exception e) {
        if (e instanceof org.springframework.web.bind.MissingServletRequestParameterException m) {
            return respond(ErrorCode.VALIDATION_ERROR, "Campo obrigatório ausente: " + m.getParameterName() + ".",
                    List.of(new ApiProblem.FieldError(m.getParameterName(), "Obrigatório")));
        }
        return respond(ErrorCode.VALIDATION_ERROR, "Formato não aceito: envie o arquivo em multipart/form-data (só o nome não basta).",
                List.of(new ApiProblem.FieldError("file", "Arquivo obrigatório")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalid(MethodArgumentNotValidException e) {
        List<ApiProblem.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiProblem.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "Corrija os campos indicados.", errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> unreadable(Exception e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Requisição malformada.", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiProblem> noResource(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, "Recurso inexistente.", List.of());
    }

    /** F16: banco fora/sem conexão = 503 rápido e honesto (o cliente tenta de novo); nada foi gravado. */
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<ApiProblem> databaseDown(Exception e) {
        log.warn("Banco indisponível request_id={} tipo={}", RequestIds.current(), e.getClass().getSimpleName());
        return respond(ErrorCode.INTEGRATION_UNAVAILABLE, "Serviço temporariamente indisponível (banco de dados). "
                + "Nada foi gravado; tente novamente em instantes.", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> unexpected(Exception e) {
        if (br.com.engenheiro.api.shared.ops.FailSafeFilter.databaseDown(e)) {
            return databaseDown(e);
        }
        // Só tipo e pilha: a mensagem original pode trazer valores da linha (CPF, conta) — OPS-03.
        log.error("Erro inesperado request_id={}", RequestIds.current(), br.com.engenheiro.api.shared.ops.LogSanitizer.scrub(e));
        return ResponseEntity.internalServerError().contentType(PROBLEM_JSON)
                .body(new ApiProblem("about:blank", "Erro interno", 500, "INTERNAL_ERROR", RequestIds.current(),
                        "Erro inesperado. Informe o request_id ao suporte.", List.of()));
    }

    private ResponseEntity<ApiProblem> respond(ErrorCode code, String detail, List<ApiProblem.FieldError> errors) {
        return ResponseEntity.status(code.status()).contentType(PROBLEM_JSON)
                .body(ApiProblem.of(code, detail, errors));
    }
}
