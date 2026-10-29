package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.TicketState;

public final class IllegalTransitionException extends DomainException {

    public IllegalTransitionException(TicketState from, TicketState to) {
        super("Transition %s -> %s is not allowed".formatted(from, to));
    }
}
