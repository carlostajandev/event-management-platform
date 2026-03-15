package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.Venue;

import java.time.Instant;

/**
 * Core domain entity representing a ticketed event.
 *
 * <p>Immutable by design — uses Java Records. Any state change
 * produces a new instance, making it safe for concurrent access.
 *
 * <p>This class has ZERO framework dependencies — no Spring, no AWS,
 * no Jackson annotations. Infrastructure concerns belong in adapters.
 *
 * @param eventId        Unique identifier (evt_UUID format)
 * @param name           Event name (e.g. "Bad Bunny World Tour")
 * @param description    Optional description
 * @param eventDate      When the event takes place (UTC)
 * @param venue          Physical location
 * @param totalCapacity  Maximum number of tickets that can be sold
 * @param availableTickets Current available ticket count
 * @param ticketPrice    Price per ticket
 * @param status         Current event status
 * @param createdAt      Creation timestamp (UTC)
 * @param updatedAt      Last update timestamp (UTC)
 * @param version        Optimistic locking version — increments on every update
 */
public record Event(
        EventId eventId,
        String name,
        String description,
        Instant eventDate,
        Venue venue,
        int totalCapacity,
        int availableTickets,
        Money ticketPrice,
        EventStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    public enum EventStatus {
        DRAFT,
        PUBLISHED,
        SOLD_OUT,
        CANCELLED,
        COMPLETED
    }

    /**
     * Factory method — creates a new event with generated ID and initial state.
     * Sets availableTickets = totalCapacity on creation.
     */
    public static Event create(
            String name,
            String description,
            Instant eventDate,
            Venue venue,
            int totalCapacity,
            Money ticketPrice) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Event name cannot be blank");
        }
        if (totalCapacity <= 0) {
            throw new IllegalArgumentException("Total capacity must be greater than zero");
        }
        if (eventDate == null || eventDate.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Event date must be in the future");
        }

        Instant now = Instant.now();
        return new Event(
                EventId.generate(),
                name,
                description,
                eventDate,
                venue,
                totalCapacity,
                totalCapacity,   // availableTickets starts equal to totalCapacity
                ticketPrice,
                EventStatus.DRAFT,
                now,
                now,
                0L               // version starts at 0
        );
    }

    /**
     * Returns a new Event with status PUBLISHED.
     * Immutable — produces a new instance.
     */
    public Event publish() {
        return new Event(
                eventId, name, description, eventDate, venue,
                totalCapacity, availableTickets, ticketPrice,
                EventStatus.PUBLISHED,
                createdAt, Instant.now(), version + 1
        );
    }

    /**
     * Returns a new Event with decremented availableTickets.
     * Called when tickets are reserved.
     */
    public Event reserveTickets(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (availableTickets < quantity) {
            throw new IllegalStateException(
                    "Not enough tickets available. Requested: %d, Available: %d"
                            .formatted(quantity, availableTickets));
        }
        int newAvailable = availableTickets - quantity;
        EventStatus newStatus = newAvailable == 0 ? EventStatus.SOLD_OUT : this.status;

        return new Event(
                eventId, name, description, eventDate, venue,
                totalCapacity, newAvailable, ticketPrice,
                newStatus,
                createdAt, Instant.now(), version + 1
        );
    }

    /**
     * Returns a new Event with incremented availableTickets.
     * Called when a reservation expires or a purchase is cancelled.
     */
    public Event releaseTickets(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        int newAvailable = Math.min(availableTickets + quantity, totalCapacity);
        EventStatus newStatus = this.status == EventStatus.SOLD_OUT
                ? EventStatus.PUBLISHED
                : this.status;

        return new Event(
                eventId, name, description, eventDate, venue,
                totalCapacity, newAvailable, ticketPrice,
                newStatus,
                createdAt, Instant.now(), version + 1
        );
    }

    public boolean isAvailable() {
        return status == EventStatus.PUBLISHED && availableTickets > 0;
    }

    public boolean isSoldOut() {
        return status == EventStatus.SOLD_OUT || availableTickets == 0;
    }
}
