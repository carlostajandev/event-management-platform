package com.nequi.ticketing.shared.error;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.InvalidTicketStateException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.exception.TicketNotAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Global reactive error handler for all unhandled exceptions in WebFlux.
 *
 * <p>Uses Java 25 pattern matching switch — type-safe, exhaustive,
 * no string comparison on class names.
 *
 * <p>Before (Java 11 style): switch on ex.getClass().getSimpleName() — brittle.
 * After (Java 25): pattern matching on the actual type — refactor-safe.
 */
@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = resolveStatus(ex);
        String message = resolveMessage(ex);

        if (status.is5xxServerError()) {
            log.error("Server error [{} {}]: {}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    ex.getMessage(), ex);
        } else {
            log.warn("Client error [{} {}] -> {}: {}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    status.value(),
                    ex.getMessage());
        }

        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                exchange.getRequest().getPath().value(),
                Instant.now().toString()
        );

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorResponse);
        } catch (Exception e) {
            bytes = "{\"error\":\"Internal error\"}".getBytes();
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * Java 25 pattern matching switch — type-safe, no string comparison.
     * Each case binds the exception to a typed variable (e),
     * allowing field access without casting.
     */
    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return HttpStatus.valueOf(rse.getStatusCode().value());
        }
        return switch (ex) {
            case TicketNotAvailableException e   -> HttpStatus.CONFLICT;
            case EventNotFoundException e        -> HttpStatus.NOT_FOUND;
            case OrderNotFoundException e        -> HttpStatus.NOT_FOUND;
            case InvalidTicketStateException e   -> HttpStatus.UNPROCESSABLE_ENTITY;
            default                              -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String resolveMessage(Throwable ex) {
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return "An unexpected error occurred";
    }
}