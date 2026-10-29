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

/** Issues courtesy tickets straight to COMPLIMENTARY: final, free and not enqueued. */
public final class IssueComplimentaryUseCase extends OrderCreation {

    public IssueComplimentaryUseCase(EventRepository events, OrderRepository orders, IdempotentReplay idempotentReplay,
                                     Clock clock) {
        super(events, orders, idempotentReplay, clock);
    }

    @Override
    StateTransition transition(OrderId id, Event event, PlaceOrderCommand command, Instant now) {
        return Order.complimentary(id, event, command.userId(), command.quantity(), now);
    }
}
