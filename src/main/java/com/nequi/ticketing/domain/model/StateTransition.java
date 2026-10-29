package com.nequi.ticketing.domain.model;

import java.time.Instant;

/**
 * Result of a state change on an order. Persistence applies it as one atomic write: the order in
 * {@code to} state (conditional on {@code from}), the inventory {@code move} and an audit record.
 */
public record StateTransition(Order order, TicketState from, TicketState to, InventoryMove move, Instant at) {

    static StateTransition of(Order order, TicketState from, Instant at) {
        return new StateTransition(order, from, order.status(), new InventoryMove(from, order.status(), order.quantity()), at);
    }
}
