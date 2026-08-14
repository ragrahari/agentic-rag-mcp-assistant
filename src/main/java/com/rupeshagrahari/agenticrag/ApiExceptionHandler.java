package com.rupeshagrahari.agenticrag;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Puts the "reason" the code already chose (e.g. the guardrail's "Tenant not recognized
 * or inactive") into the JSON response body, alongside the HTTP status. Spring Boot's
 * default error body omits the exception message unless
 * `server.error.include-message=always` is set globally -- turning that on would also
 * expose raw messages from unrelated, unreviewed exceptions across the app. Handling
 * {@link ResponseStatusException} specifically keeps this to messages this codebase
 * deliberately wrote for callers to see (this guardrail denial, {@link IngestionService}'s
 * 404), without widening what gets surfaced from anything else.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();

        return ResponseEntity.status(status)
                .body(Map.of("status", status.value(), "error", status.getReasonPhrase(), "reason", reason));
    }

}
