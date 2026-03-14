package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.OrderResponse;
import reactor.core.publisher.Mono;

/**
 * Driving port for querying order status.
 */
public interface QueryOrderStatusUseCase {

    /**
     * Returns the current status of a purchase order.
     *
     * @param orderId the order identifier
     * @return order details including current status
     */
    Mono<OrderResponse> findById(String orderId);
}
