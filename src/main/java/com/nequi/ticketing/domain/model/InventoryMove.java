package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

/** Moves {@code quantity} tickets of one event from one state counter to another. */
public record InventoryMove(TicketState from, TicketState to, int quantity) {

    public InventoryMove {
        if (from == null || to == null || quantity <= 0) {
            throw new DomainValidationException("An inventory move needs two states and a positive quantity");
        }
    }
}
