package com.nequi.ticketing.domain.port;

import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OrderRepository {

    /**
     * Creates an order from AVAILABLE in one atomic write: inventory move (conditional on enough
     * available tickets), the order, the idempotency record, the audit record and, for a purchase,
     * the outbox message that hands the order to the consumer.
     */
    Mono<Order> create(StateTransition creation, IdempotencyRecord idempotency);

    /**
     * Applies a transition of an existing order in one atomic write, conditional on the order still
     * being in {@code transition.from()} and on its deadline. Fails with {@code OrderStateChangedException}
     * otherwise.
     */
    Mono<Order> apply(StateTransition transition);

    Mono<Order> findById(OrderId id);

    /** Orders still holding tickets whose deadline is at or before {@code now}, oldest first. */
    Flux<Order> findExpiredHolds(Instant now, int limit);
}
