package com.nequi.ticketing.application.order;

import com.nequi.ticketing.application.idempotency.IdempotentReplay;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.OrderRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/** Shared flow for creating an order from AVAILABLE: load event, build the transition, write it idempotently. */
abstract class OrderCreation {

    private final EventRepository events;
    private final OrderRepository orders;
    private final IdempotentReplay idempotentReplay;
    private final Clock clock;

    OrderCreation(EventRepository events, OrderRepository orders, IdempotentReplay idempotentReplay, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.idempotentReplay = idempotentReplay;
        this.clock = clock;
    }

    public Mono<Order> execute(PlaceOrderCommand command) {
        return idempotentReplay.execute(command.idempotencyKey(), command.requestHash(),
                () -> create(command),
                id -> orders.findById(new OrderId(id)).switchIfEmpty(Mono.error(new NotFoundException("Order", id))));
    }

    abstract StateTransition transition(OrderId id, Event event, PlaceOrderCommand command, Instant now);

    private Mono<Order> create(PlaceOrderCommand command) {
        return events.findById(command.eventId())
                .switchIfEmpty(Mono.error(() -> new NotFoundException("Event", command.eventId().value())))
                .flatMap(event -> {
                    Instant now = clock.instant();
                    StateTransition transition = transition(OrderId.random(), event, command, now);
                    IdempotencyRecord idempotency = new IdempotencyRecord(command.idempotencyKey(),
                            command.requestHash(), transition.order().id().value(), now);
                    return orders.create(transition, idempotency);
                });
    }
}
