package com.nequi.ticketing.application.order;

import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.TicketState;
import com.nequi.ticketing.domain.port.OrderRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Consumer side of RF3. Validates that the order still holds its tickets (it is RESERVED and its deadline has
 * not passed) and moves it to PENDING_CONFIRMATION, ready for payment. Delivery is at least once, so every
 * outcome other than a technical failure is final: duplicates and late messages are acknowledged without
 * changing anything.
 */
public final class ProcessPlacedOrderUseCase {

    public enum Outcome {
        /** RESERVED -> PENDING_CONFIRMATION applied. */
        ACCEPTED,
        /** Already past RESERVED: duplicate delivery or a concurrent change. */
        ALREADY_PROCESSED,
        /** Deadline passed before the message was processed; the expiry job releases the tickets. */
        EXPIRED,
        /** No such order. */
        NOT_FOUND
    }

    private final OrderRepository orders;
    private final Clock clock;

    public ProcessPlacedOrderUseCase(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    public Mono<Outcome> execute(OrderId id) {
        return orders.findById(id)
                .flatMap(this::accept)
                .defaultIfEmpty(Outcome.NOT_FOUND);
    }

    private Mono<Outcome> accept(Order order) {
        Instant now = clock.instant();
        if (order.status() != TicketState.RESERVED) {
            return Mono.just(Outcome.ALREADY_PROCESSED);
        }
        if (order.isExpired(now)) {
            return Mono.just(Outcome.EXPIRED);
        }
        return orders.apply(order.startConfirmation(now))
                .thenReturn(Outcome.ACCEPTED)
                .onErrorResume(OrderStateChangedException.class, changed -> Mono.just(Outcome.ALREADY_PROCESSED));
    }
}
