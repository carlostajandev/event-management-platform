package com.nequi.orderservice.application.port.in;

import com.nequi.orderservice.application.command.CreateOrderCommand;
import com.nequi.orderservice.application.dto.OrderResponse;
import reactor.core.publisher.Mono;

/**
 * Input port for creating a new order from an active reservation.
 *
 * <p>Idempotency contract: if a request with the same {@code idempotencyKey} has already
 * been processed, the cached {@link OrderResponse} is returned without re-executing
 * any business logic or writing to DynamoDB.
 *
 * @see com.nequi.orderservice.application.usecase.CreateOrderService
 */
public interface CreateOrderUseCase {

    /**
     * Executes the create-order use case.
     *
     * @param command immutable command containing reservationId, userId and idempotencyKey
     * @return a {@link Mono} emitting the {@link OrderResponse} — either newly created or cached
     */
    Mono<OrderResponse> execute(CreateOrderCommand command);
}
