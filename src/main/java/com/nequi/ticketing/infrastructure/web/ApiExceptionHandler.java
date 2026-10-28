package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.domain.exception.DomainException;
import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.exception.HoldExpiredException;
import com.nequi.ticketing.domain.exception.IdempotencyKeyReusedException;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;
import com.nequi.ticketing.domain.exception.InsufficientTicketsException;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.TransactionContentionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Maps errors to RFC 9457 problem details. Each problem carries a stable {@code code} clients can branch on.
 * Framework errors (malformed JSON, failed bean validation, missing headers) are handled by the base class.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private record Mapping(HttpStatus status, String code) {
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException exception) {
        Mapping mapping = switch (exception) {
            case DomainValidationException _ -> new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
            case NotFoundException _ -> new Mapping(HttpStatus.NOT_FOUND, "NOT_FOUND");
            case InsufficientTicketsException _ -> new Mapping(HttpStatus.CONFLICT, "SOLD_OUT");
            case IllegalTransitionException _ -> new Mapping(HttpStatus.CONFLICT, "INVALID_STATE");
            case HoldExpiredException _ -> new Mapping(HttpStatus.CONFLICT, "HOLD_EXPIRED");
            case OrderStateChangedException _ -> new Mapping(HttpStatus.CONFLICT, "STATE_CHANGED");
            case DuplicateRequestException _ -> new Mapping(HttpStatus.CONFLICT, "DUPLICATE_REQUEST");
            case IdempotencyKeyReusedException _ -> new Mapping(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED");
        };
        return problem(mapping.status(), mapping.code(), exception.getMessage());
    }

    @ExceptionHandler(CallerNotIdentifiedException.class)
    ResponseEntity<ProblemDetail> unidentified(CallerNotIdentifiedException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "CALLER_NOT_IDENTIFIED", exception.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> forbidden(ForbiddenException exception) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage());
    }

    /** Hot inventory item under heavy contention: shed load and ask the client to retry shortly. */
    @ExceptionHandler(TransactionContentionException.class)
    ResponseEntity<ProblemDetail> contention(TransactionContentionException exception) {
        log.warn("Transaction contention after retries: {}", exception.getCause() == null ? "" : exception.getCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, "CONTENTION", exception.getMessage()));
    }

    /**
     * The call to DynamoDB failed on the client side (timeouts, aborted connections) after the SDK retries, so its
     * outcome is unknown: a write may still have committed. The client is told to retry with the same
     * Idempotency-Key, which returns the original result instead of writing again. Errors DynamoDB answered with
     * are not client exceptions and stay 500.
     */
    @ExceptionHandler(SdkClientException.class)
    ResponseEntity<ProblemDetail> timeout(SdkClientException exception) {
        log.warn("Datastore timeout: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, "TIMEOUT",
                        "The request did not complete in time and may have been applied; "
                                + "retry with the same Idempotency-Key to get its result"));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(body(status, code, detail));
    }

    private static ProblemDetail body(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
