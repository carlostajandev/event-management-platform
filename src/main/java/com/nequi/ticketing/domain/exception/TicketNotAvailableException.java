package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.valueobject.EventId;

/**
 * Thrown when tickets are requested but none are available.
 * Mapped to HTTP 409 Conflict by GlobalErrorHandler.
 */
public class TicketNotAvailableException extends RuntimeException {

    private final EventId eventId;
    private final int requested;

    public TicketNotAvailableException(EventId eventId, int requested) {
        super("No tickets available for event %s. Requested: %d"
                .formatted(eventId.value(), requested));
        this.eventId = eventId;
        this.requested = requested;
    }

    public EventId getEventId() { return eventId; }
    public int getRequested() { return requested; }
}
