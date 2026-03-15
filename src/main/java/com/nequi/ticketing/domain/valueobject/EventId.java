package com.nequi.ticketing.domain.valueobject;

import java.util.UUID;

/**
 * Strongly-typed identifier for an Event.
 *
 * <p>Using a dedicated record instead of a raw String prevents accidentally
 * passing an OrderId where an EventId is expected — the compiler catches it.
 */
public record EventId(String value) {

    public EventId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EventId cannot be null or blank");
        }
    }

    public static EventId generate() {
        return new EventId("evt_" + UUID.randomUUID());
    }

    public static EventId of(String value) {
        return new EventId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
