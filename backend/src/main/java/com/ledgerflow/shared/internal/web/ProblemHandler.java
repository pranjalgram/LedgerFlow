package com.ledgerflow.shared.internal.web;

import com.ledgerflow.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemHandler {
    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ProblemDetail validation(HttpServletRequest request) {
        return problem(400, "invalid-request", "The request is malformed or contains invalid fields.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(HttpServletRequest request) {
        return problem(409, "constraint-conflict", "The operation conflicts with an existing record or invariant.", request);
    }

    @ExceptionHandler({CannotAcquireLockException.class, QueryTimeoutException.class})
    ResponseEntity<ProblemDetail> busy(HttpServletRequest request) {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(problem(503, "temporarily-busy", "Retry this command using the same idempotency key.", request));
    }

    private ProblemDetail problem(int status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(URI.create("urn:ledgerflow:problem:" + code));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", request.getAttribute("requestId"));
        return problem;
    }
}
