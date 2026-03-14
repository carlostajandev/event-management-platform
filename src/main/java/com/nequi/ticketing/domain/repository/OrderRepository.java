package com.nequi.ticketing.domain.repository;

import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.valueobject.OrderId;
import reactor.core.publisher.Mono;

/**
 * Domain repository interface for Order persistence.
 */
public interface OrderRepository {

    Mono<Order> save(Order order);

    Mono<Order> findById(OrderId orderId);

    Mono<Order> update(Order order);
}
