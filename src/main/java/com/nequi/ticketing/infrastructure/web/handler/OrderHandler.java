package com.nequi.ticketing.infrastructure.web.handler;

import com.nequi.ticketing.application.dto.ReserveTicketsCommand;
import com.nequi.ticketing.application.port.in.QueryOrderStatusUseCase;
import com.nequi.ticketing.application.port.in.ReserveTicketsUseCase;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * WebFlux handler for Order/Reservation endpoints.
 */
@Component
public class OrderHandler {

    private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

    private final ReserveTicketsUseCase reserveTicketsUseCase;
    private final QueryOrderStatusUseCase queryOrderStatusUseCase;
    private final Validator validator;

    public OrderHandler(
            ReserveTicketsUseCase reserveTicketsUseCase,
            QueryOrderStatusUseCase queryOrderStatusUseCase,
            Validator validator) {
        this.reserveTicketsUseCase = reserveTicketsUseCase;
        this.queryOrderStatusUseCase = queryOrderStatusUseCase;
        this.validator = validator;
    }

    /**
     * POST /api/v1/orders
     */
    public Mono<ServerResponse> createOrder(ServerRequest request) {
        String idempotencyKey = request.headers().firstHeader(IDEMPOTENCY_KEY_HEADER);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ServerResponse.badRequest()
                    .bodyValue("Missing required header: " + IDEMPOTENCY_KEY_HEADER);
        }

        return request.bodyToMono(ReserveTicketsCommand.class)
                .map(cmd -> new ReserveTicketsCommand(
                        cmd.eventId(), cmd.userId(), cmd.quantity(), idempotencyKey))
                .flatMap(this::validate)
                .flatMap(reserveTicketsUseCase::execute)
                .flatMap(response -> ServerResponse
                        .status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response));
    }

    /**
     * GET /api/v1/orders/{orderId}
     */
    public Mono<ServerResponse> getOrderStatus(ServerRequest request) {
        String orderId = request.pathVariable("orderId");

        return queryOrderStatusUseCase.findById(orderId)
                .flatMap(response -> ServerResponse
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response));
    }

    private <T> Mono<T> validate(T body) {
        var violations = validator.validate(body);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Validation failed");
            return Mono.error(new jakarta.validation.ValidationException(message));
        }
        return Mono.just(body);
    }
}