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

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> unexpected(Exception e) {
        log.error("Erro inesperado request_id={}", RequestIds.current(), e);
        return ResponseEntity.internalServerError().contentType(PROBLEM_JSON)
                .body(new ApiProblem("about:blank", "Erro interno", 500, "INTERNAL_ERROR", RequestIds.current(),
                        "Erro inesperado. Informe o request_id ao suporte.", List.of()));
    }

    private ResponseEntity<ApiProblem> respond(ErrorCode code, String detail, List<ApiProblem.FieldError> errors) {
        return ResponseEntity.status(code.status()).contentType(PROBLEM_JSON)
                .body(ApiProblem.of(code, detail, errors));
    }
}
