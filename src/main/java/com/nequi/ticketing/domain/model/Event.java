package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

import java.time.Instant;

public record Event(
        EventId id,
        String name,
        String venue,
        Instant date,
        int capacity,
        Money ticketPrice,
        Instant createdAt) {

    public static final int MAX_CAPACITY = 200_000;
    private static final int MAX_TEXT_LENGTH = 200;

    public Event {
        if (id == null || date == null || ticketPrice == null || createdAt == null) {
            throw new DomainValidationException("Event requires id, date, ticket price and creation time");
        }
        requireText("name", name);
        requireText("venue", venue);
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new DomainValidationException("Capacity must be between 1 and %d".formatted(MAX_CAPACITY));
        }
    }

    public static Event create(EventId id, String name, String venue, Instant date, int capacity,
                               Money ticketPrice, Instant now) {
        if (date != null && !date.isAfter(now)) {
            throw new DomainValidationException("Event date must be in the future");
        }
        return new Event(id, name, venue, date, capacity, ticketPrice, now);
    }

    public boolean hasHappened(Instant now) {
        return !now.isBefore(date);
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new DomainValidationException("%s must be 1-%d non-blank characters".formatted(field, MAX_TEXT_LENGTH));
        }
    }
}
