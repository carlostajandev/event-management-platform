package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.TicketStatus;

/**
 * Thrown when an illegal ticket state transition is attempted.
 * Mapped to HTTP 422 Unprocessable Entity by GlobalErrorHandler.
 */
public class InvalidTicketStateException extends RuntimeException {

    private final TicketStatus from;
    private final TicketStatus to;

    public InvalidTicketStateException(TicketStatus from, TicketStatus to) {
        super("Invalid ticket state transition: %s → %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public TicketStatus getFrom() { return from; }
    public TicketStatus getTo() { return to; }
}
