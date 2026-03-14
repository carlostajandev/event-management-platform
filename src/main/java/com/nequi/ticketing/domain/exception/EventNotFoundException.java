package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.valueobject.EventId;

/**
 * Thrown when an event is not found by its identifier.
 * Mapped to HTTP 404 by {@link com.nequi.ticketing.shared.error.GlobalErrorHandler}.
 */
public class EventNotFoundException extends RuntimeException {

    private final EventId eventId;

    public EventNotFoundException(EventId eventId) {
        super("Event not found: " + eventId.value());
        this.eventId = eventId;
    }

    public EventId getEventId() {
        return eventId;
    }
}
