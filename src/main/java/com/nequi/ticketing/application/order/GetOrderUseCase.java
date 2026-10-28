package com.nequi.ticketing.application.order;

import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.UserId;
import com.nequi.ticketing.domain.port.OrderRepository;
import reactor.core.publisher.Mono;

/** Order status (RF4). Another caller's order is reported as not found, so ids cannot be probed. */
public final class GetOrderUseCase {

    private final OrderRepository orders;

    public GetOrderUseCase(OrderRepository orders) {
        this.orders = orders;
    }

    public Mono<Order> execute(OrderId id, UserId caller) {
        return orders.findById(id)
                .filter(order -> order.userId().equals(caller))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("Order", id.value())));
    }
}
