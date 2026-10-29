package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.TicketState;

/** The order was no longer in the expected state (or its deadline moved past) when the write ran. */
public final class OrderStateChangedException extends DomainException {

    public OrderStateChangedException(OrderId orderId, TicketState expected) {
        super("Order %s is no longer %s".formatted(orderId.value(), expected));
    }
}
