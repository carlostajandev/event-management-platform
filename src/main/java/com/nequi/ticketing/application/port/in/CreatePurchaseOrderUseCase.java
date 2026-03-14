package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.CreatePurchaseOrderCommand;
import com.nequi.ticketing.application.dto.OrderResponse;
import reactor.core.publisher.Mono;

/**
 * Driving port for creating a purchase order.
 *
 * <p>Saves the order as PENDING and enqueues it in SQS.
 * Returns immediately with the orderId — processing is async.
 */
public interface CreatePurchaseOrderUseCase {

    Mono<OrderResponse> execute(CreatePurchaseOrderCommand command);
}
