package com.nequi.shared.domain.port;

import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderRepository {

    /**
     * Saves order and outbox message in the same DynamoDB TransactWriteItems.
     * This guarantees that if SQS is down, the outbox poller will retry delivery
     * without creating a zombie order.
     *
     * @param order         the order to persist
     * @param outboxPayload JSON payload to write to emp-outbox
     */
    Mono<Order> saveWithOutbox(Order order, String outboxPayload);

    Mono<Order> findById(String orderId);

    Mono<Order> findByReservationId(String reservationId);

    Flux<Order> findByUserId(String userId);

    Mono<Order> updateStatus(String orderId, OrderStatus newStatus);
}