package com.nequi.shared.infrastructure.util;

import com.nequi.shared.domain.exception.DomainException;
import com.nequi.shared.domain.exception.NotFoundException;
import com.nequi.shared.domain.exception.BusinessRuleException;
import com.nequi.shared.domain.exception.ConflictException;
import com.nequi.shared.domain.exception.IdempotencyConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global error handler using Java 25 pattern matching switch.
 * Maps domain exceptions to appropriate HTTP status codes.
 */
@Slf4j
@Order(-2)
@Component
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // Java 25 pattern matching switch — hierarchy-aware
        HttpStatus status = switch (ex) {
            case NotFoundException ignored            -> HttpStatus.NOT_FOUND;
            case BusinessRuleException ignored        -> HttpStatus.UNPROCESSABLE_ENTITY;
            case IdempotencyConflictException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ConflictException ignored            -> HttpStatus.CONFLICT;
            case IllegalArgumentException ignored     -> HttpStatus.BAD_REQUEST;
            default -> {
                log.error("Unhandled exception", ex);
                yield HttpStatus.INTERNAL_SERVER_ERROR;
            }
        };

        String errorCode = ex instanceof DomainException de ? de.errorCode() : "INTERNAL_ERROR";

        String traceId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (traceId == null) traceId = UUID.randomUUID().toString();

        ErrorResponse body = ErrorResponse.of(
                status.value(), status.getReasonPhrase(), errorCode, ex.getMessage(), traceId);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception jsonEx) {
            return exchange.getResponse().setComplete();
        }
    }
}