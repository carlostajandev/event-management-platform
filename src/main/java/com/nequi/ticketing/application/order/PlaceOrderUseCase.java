package com.nequi.ticketing.application.order;

import com.nequi.ticketing.application.idempotency.IdempotentReplay;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.OrderRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * Starts a purchase (RF2, RF3): holds the tickets for 10 minutes and enqueues the order for the consumer in the
 * same transaction. Returns the RESERVED order immediately.
 */
public final class PlaceOrderUseCase extends OrderCreation {

    public PlaceOrderUseCase(EventRepository events, OrderRepository orders, IdempotentReplay idempotentReplay,
                             Clock clock) {
        super(events, orders, idempotentReplay, clock);
    }

    @Override
    StateTransition transition(OrderId id, Event event, PlaceOrderCommand command, Instant now) {
        return Order.place(id, event, command.userId(), command.quantity(), now);
    }
}
