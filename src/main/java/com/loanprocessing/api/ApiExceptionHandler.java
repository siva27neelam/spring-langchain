package com.loanprocessing.api;

import com.loanprocessing.security.RequestContext;
import com.loanprocessing.support.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException exception) {
        return problem(exception.status(), exception.reasonCode(), exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> database(DataAccessException exception) {
        return problem(503, "DATABASE_UNAVAILABLE", "service unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        return problem(500, "UNEXPECTED", "unexpected error");
    }

    private static ResponseEntity<Map<String, Object>> problem(int status, String reason, String detail) {
        return ResponseEntity.status(status).header("Content-Type", "application/problem+json").body(Map.of(
                "type", "about:blank",
                "title", reason,
                "status", status,
                "detail", detail,
                "correlationId", RequestContext.get().correlationId().toString(),
                "reasonCode", reason
        ));
    }
}
