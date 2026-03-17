package com.nequi.shared.domain.exception;

public class TicketNotAvailableException extends BusinessRuleException {

    public TicketNotAvailableException(String eventId, int requested, int available) {
        super("TICKET_NOT_AVAILABLE",
                "Tickets not available for event %s: requested=%d, available=%d"
                        .formatted(eventId, requested, available));
    }

    public TicketNotAvailableException(String eventId) {
        super("TICKET_NOT_AVAILABLE", "No tickets available for event: " + eventId);
    }
}
