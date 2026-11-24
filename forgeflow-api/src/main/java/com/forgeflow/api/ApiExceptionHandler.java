package com.forgeflow.api;

import com.forgeflow.common.InvalidTransitionException;
import com.forgeflow.common.SessionNotFoundException;
import com.forgeflow.common.TaskNotFoundException;
import com.forgeflow.common.TenantQuotaExceededException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail notFound(TaskNotFoundException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Task not found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail sessionNotFound(SessionNotFoundException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Session not found");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler({InvalidTransitionException.class, IllegalStateException.class})
    public ProblemDetail conflict(RuntimeException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Invalid task transition");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(TenantQuotaExceededException.class)
    public ProblemDetail quotaExceeded(TenantQuotaExceededException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        detail.setTitle("Tenant quota exceeded");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid request");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ProblemDetail dependencyUnavailable(RuntimeException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        detail.setTitle("Dependency unavailable");
        detail.setDetail("A backing store (database or redis) is not reachable; retry later.");
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail badRequest(MethodArgumentNotValidException ex) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid request");
        detail.setDetail(ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed"));
        return detail;
    }
}
