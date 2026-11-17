package com.ags.transactions.api;

import com.ags.transactions.idempotency.IdempotencyKeyInProgressException;
import com.ags.transactions.idempotency.IdempotencyKeyReusedException;
import com.ags.transactions.transaction.InvalidSettlementException;
import com.ags.transactions.transaction.TransactionNotFoundException;
import com.ags.transactions.transaction.TransactionNotPendingException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as RFC 9457 (formerly 7807) application/problem+json.
 *
 * <p>Extending ResponseEntityExceptionHandler gives problem+json for Spring's own errors
 * (malformed JSON, a non-UUID id, an unknown status in a query parameter: all 400). The
 * handlers below map our domain exceptions, one status code each:
 *
 * <pre>
 *   400  validation failure, bad Idempotency-Key header, invalid PATCH body
 *   404  transaction not found
 *   409  transaction is not PENDING (includes losing a concurrent PATCH race)
 *   422  Idempotency-Key reused with a different request body
 *   503  same Idempotency-Key still in flight after the wait; retry with the same key
 * </pre>
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** How long a client should wait before retrying a POST whose key was still in flight. */
    static final String RETRY_AFTER_SECONDS = "1";

    /** One entry per invalid field in a 400, so a client can show every problem at once. */
    record FieldViolation(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail body = ex.getBody();
        body.setTitle("Validation failed");
        body.setDetail("The request body has " + violations.size() + " invalid field(s)");
        body.setProperty("errors", violations);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler({InvalidIdempotencyKeyException.class, InvalidSettlementException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail notFound(TransactionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Transaction not found", e.getMessage());
    }

    @ExceptionHandler(TransactionNotPendingException.class)
    ProblemDetail notPending(TransactionNotPendingException e) {
        ProblemDetail body = problem(HttpStatus.CONFLICT, "Transaction is not pending", e.getMessage());
        body.setProperty("currentStatus", e.getCurrentStatus());
        return body;
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    ResponseEntity<ProblemDetail> keyInProgress(IdempotencyKeyInProgressException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Request in progress", e.getMessage()));
    }

    /** Anything unexpected: log it with the stack trace, but don't leak internals to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return body;
    }
}
