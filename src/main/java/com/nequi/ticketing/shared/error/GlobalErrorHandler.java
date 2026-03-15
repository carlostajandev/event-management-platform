package com.nequi.ticketing.shared.error;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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

import java.time.Instant;

/**
 * Global reactive error handler for all unhandled exceptions in WebFlux.
 *
 * <p>Order(-2) ensures this runs before Spring Boot's default error handler.
 * Maps domain and infrastructure exceptions to appropriate HTTP status codes
 * and returns a consistent {@link ErrorResponse} JSON body.
 */
@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

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

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return HttpStatus.valueOf(rse.getStatusCode().value());
        }
        return switch (ex.getClass().getSimpleName()) {
            case "TicketNotAvailableException"      -> HttpStatus.CONFLICT;
            case "OrderNotFoundException"           -> HttpStatus.NOT_FOUND;
            case "EventNotFoundException"           -> HttpStatus.NOT_FOUND;
            case "InvalidTicketStateException"      -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "DuplicateIdempotencyKeyException" -> HttpStatus.OK;
            case "MaxTicketsExceededException"      -> HttpStatus.BAD_REQUEST;
            case "ValidationException"              -> HttpStatus.BAD_REQUEST;
            case "ConstraintViolationException"     -> HttpStatus.BAD_REQUEST;
            default                                 -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String resolveMessage(Throwable ex) {
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return "An unexpected error occurred";
    }
}