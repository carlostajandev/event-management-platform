package com.nequi.orderservice.application.port.in;

import com.nequi.orderservice.application.dto.OrderResponse;
import reactor.core.publisher.Mono;

/**
 * Input port for retrieving the current status of an order.
 *
 * @see com.nequi.orderservice.application.usecase.GetOrderStatusService
 */
public interface GetOrderStatusUseCase {

    /**
     * Retrieves an order by its id.
     *
     * @param orderId the order identifier
     * @return a {@link Mono} emitting the {@link OrderResponse}, or an error signal
     *         with {@link com.nequi.shared.domain.exception.OrderNotFoundException} if not found
     */
    Mono<OrderResponse> execute(String orderId);
}
