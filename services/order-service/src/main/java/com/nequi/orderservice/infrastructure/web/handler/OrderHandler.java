package com.nequi.orderservice.infrastructure.web.handler;

import com.nequi.orderservice.application.command.CreateOrderCommand;
import com.nequi.orderservice.application.dto.CreateOrderRequest;
import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.orderservice.application.port.in.CreateOrderUseCase;
import com.nequi.orderservice.application.port.in.GetOrderStatusUseCase;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WebFlux functional handler for the Order resource.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Parsing and validating request bodies / path variables / headers</li>
 *   <li>Extracting the mandatory {@code X-Idempotency-Key} header (400 if absent)</li>
 *   <li>Delegating to the appropriate input port (use-case)</li>
 *   <li>Building {@link ServerResponse} with correct HTTP status codes</li>
 * </ul>
 *
 * <p>Domain exceptions propagate to {@link com.nequi.shared.infrastructure.util.GlobalErrorHandler}
 * which maps them to appropriate HTTP status codes (404, 409, 500).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderHandler {

    private static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    private static final String ORDER_ID_PATH_VAR      = "orderId";

    private final CreateOrderUseCase   createOrderUseCase;
    private final GetOrderStatusUseCase getOrderStatusUseCase;
    private final Validator            validator;

    // ── POST /api/v1/orders ───────────────────────────────────────────────────

    /**
     * Creates a new order from an active reservation.
     * Returns 201 Created with Location header on success.
     * Returns 400 if {@code X-Idempotency-Key} header is missing or body validation fails.
     */
    public Mono<ServerResponse> createOrder(ServerRequest request) {
        String idempotencyKey = request.headers().firstHeader(IDEMPOTENCY_KEY_HEADER);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Missing required header: {}", IDEMPOTENCY_KEY_HEADER);
            return ServerResponse
                    .status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"error\":\"Missing required header: X-Idempotency-Key\"}");
        }

        return request.bodyToMono(CreateOrderRequest.class)
                .flatMap(this::validateRequest)
                .map(req -> new CreateOrderCommand(req.reservationId(), req.userId(), idempotencyKey))
                .flatMap(createOrderUseCase::execute)
                .flatMap(response ->
                        ServerResponse
                                .created(URI.create("/api/v1/orders/" + response.id()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response)
                )
                .doOnError(ex -> log.error("Error in createOrder handler: idempotencyKey={}, error={}",
                        idempotencyKey, ex.getMessage()));
    }

    // ── GET /api/v1/orders/{orderId} ──────────────────────────────────────────

    /**
     * Retrieves the current status of an order.
     * Returns 200 OK or propagates 404 via GlobalErrorHandler.
     */
    public Mono<ServerResponse> getOrderStatus(ServerRequest request) {
        String orderId = request.pathVariable(ORDER_ID_PATH_VAR);
        return getOrderStatusUseCase.execute(orderId)
                .flatMap(response ->
                        ServerResponse
                                .ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response)
                )
                .doOnError(ex -> log.error("Error in getOrderStatus handler: orderId={}, error={}",
                        orderId, ex.getMessage()));
    }

    // ── Validation helper ─────────────────────────────────────────────────────

    private Mono<CreateOrderRequest> validateRequest(CreateOrderRequest request) {
        Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return Mono.just(request);
        }
        String message = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        log.warn("Validation failed for CreateOrderRequest: {}", message);
        return Mono.error(new IllegalArgumentException("Validation failed: " + message));
    }
}
