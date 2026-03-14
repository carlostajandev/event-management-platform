package com.nequi.ticketing.domain.valueobject;

import java.util.UUID;

/**
 * Strongly-typed identifier for a Ticket.
 */
public record TicketId(String value) {

    public TicketId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TicketId cannot be null or blank");
        }
    }

    public static TicketId generate() {
        return new TicketId("tkt_" + UUID.randomUUID());
    }

    public static TicketId of(String value) {
        return new TicketId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
