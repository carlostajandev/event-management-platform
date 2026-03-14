package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.TicketId;

import java.time.Instant;

/**
 * Core domain entity representing a single event ticket.
 *
 * <p>Immutable by design — all state changes return new instances.
 * State transitions are validated by {@link TicketStateMachine}.
 *
 * @param ticketId      Unique identifier (tkt_UUID)
 * @param eventId       The event this ticket belongs to
 * @param userId        The user who owns/reserved this ticket (null if AVAILABLE)
 * @param orderId       The purchase order associated (null if AVAILABLE)
 * @param status        Current ticket status
 * @param price         Ticket price at time of purchase
 * @param reservedAt    When the reservation started (null if AVAILABLE)
 * @param expiresAt     When the reservation expires (null if not RESERVED)
 * @param confirmedAt   When the sale was confirmed (null if not SOLD)
 * @param createdAt     Creation timestamp
 * @param updatedAt     Last update timestamp
 * @param version       Optimistic locking version
 */
public record Ticket(
        TicketId ticketId,
        EventId eventId,
        String userId,
        String orderId,
        TicketStatus status,
        Money price,
        Instant reservedAt,
        Instant expiresAt,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    /**
     * Creates a new available ticket for an event.
     */
    public static Ticket createAvailable(EventId eventId, Money price) {
        Instant now = Instant.now();
        return new Ticket(
                TicketId.generate(),
                eventId,
                null, null,
                TicketStatus.AVAILABLE,
                price,
                null, null, null,
                now, now, 0L
        );
    }

    /**
     * Reserves this ticket for a user for a limited time (TTL minutes).
     * Transition: AVAILABLE → RESERVED
     */
    public Ticket reserve(String userId, String orderId, int ttlMinutes) {
        Instant now = Instant.now();
        return new Ticket(
                ticketId, eventId,
                userId, orderId,
                TicketStatus.RESERVED,
                price,
                now,
                now.plusSeconds((long) ttlMinutes * 60),
                null,
                createdAt, now, version + 1
        );
    }

    /**
     * Moves ticket to PENDING_CONFIRMATION — payment is being processed.
     * Transition: RESERVED → PENDING_CONFIRMATION
     */
    public Ticket confirmPending() {
        Instant now = Instant.now();
        return new Ticket(
                ticketId, eventId, userId, orderId,
                TicketStatus.PENDING_CONFIRMATION,
                price,
                reservedAt, expiresAt, null,
                createdAt, now, version + 1
        );
    }

    /**
     * Marks ticket as sold — payment confirmed.
     * Transition: PENDING_CONFIRMATION → SOLD (FINAL)
     */
    public Ticket sell() {
        Instant now = Instant.now();
        return new Ticket(
                ticketId, eventId, userId, orderId,
                TicketStatus.SOLD,
                price,
                reservedAt, null, now,
                createdAt, now, version + 1
        );
    }

    /**
     * Releases this ticket back to available pool.
     * Used when reservation expires or payment fails.
     * Transition: RESERVED | PENDING_CONFIRMATION → AVAILABLE
     */
    public Ticket release() {
        Instant now = Instant.now();
        return new Ticket(
                ticketId, eventId,
                null, null,
                TicketStatus.AVAILABLE,
                price,
                null, null, null,
                createdAt, now, version + 1
        );
    }

    /**
     * Grants this ticket as complimentary.
     * Transition: AVAILABLE → COMPLIMENTARY (FINAL)
     */
    public Ticket grantComplimentary(String userId) {
        Instant now = Instant.now();
        return new Ticket(
                ticketId, eventId,
                userId, null,
                TicketStatus.COMPLIMENTARY,
                price,
                null, null, now,
                createdAt, now, version + 1
        );
    }

    /**
     * Returns true if this reservation has expired.
     */
    public boolean isExpired() {
        return status == TicketStatus.RESERVED
                && expiresAt != null
                && Instant.now().isAfter(expiresAt);
    }

    public boolean isAvailable() {
        return status == TicketStatus.AVAILABLE;
    }
}
