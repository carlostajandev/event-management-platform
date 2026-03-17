package com.nequi.shared.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event aggregate root — Java 25 Record.
 *
 * <p>Inventory is managed as an atomic counter (availableCount) stored directly
 * on the event item in DynamoDB. This scales to millions of concurrent reservations
 * via conditional writes: {@code availableCount >= n AND version = expected}.
 *
 * <p>Write sharding: for events with >10_000 tickets, inventory is split into
 * SHARD_0…SHARD_7. Reservation picks a random shard; availability query aggregates all.
 * This prevents hot partition under high concurrency for popular events.
 */
public record Event(
        String id,
        String name,
        String description,
        Venue venue,
        Instant eventDate,
        BigDecimal ticketPrice,
        String currency,
        int totalCapacity,
        int availableCount,
        long version,           // optimistic locking — conditional write: version = :expected
        EventStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    /** Decrement availableCount for n tickets; bump version for next conditional write. */
    public Event reserveTickets(int n) {
        if (availableCount < n) {
            throw new IllegalStateException(
                    "Not enough tickets: requested=%d, available=%d".formatted(n, availableCount));
        }
        return new Event(id, name, description, venue, eventDate, ticketPrice, currency,
                totalCapacity, availableCount - n, version + 1, status, createdAt, Instant.now());
    }

    /** Release n tickets back (on reservation expiry). */
    public Event releaseTickets(int n) {
        int released = Math.min(totalCapacity, availableCount + n);
        return new Event(id, name, description, venue, eventDate, ticketPrice, currency,
                totalCapacity, released, version + 1, status, createdAt, Instant.now());
    }

    public boolean hasAvailableTickets(int requested) {
        return availableCount >= requested;
    }

    public boolean isActive() {
        return status == EventStatus.ACTIVE && eventDate.isAfter(Instant.now());
    }
}