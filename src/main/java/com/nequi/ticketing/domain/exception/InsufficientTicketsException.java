package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.EventId;

public final class InsufficientTicketsException extends DomainException {

    public InsufficientTicketsException(EventId eventId, int requested, int available) {
        super("Event %s has %d tickets available, %d requested".formatted(eventId.value(), available, requested));
    }
}
