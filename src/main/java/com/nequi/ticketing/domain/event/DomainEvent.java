package com.nequi.ticketing.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Sealed domain event hierarchy — Java 25 sealed interfaces.
 *
 * <p>Sealed types make the domain event hierarchy exhaustive and compiler-verified.
 * Every possible event is enumerated here. Pattern matching on switch over
 * DomainEvent is exhaustive — no default case needed.
 *
 * <p>Decision: sealed interface over abstract class — domain events are pure data,
 * no shared behaviour. Records implement the interface for immutability and
 * auto-generated equals/hashCode/toString.
 */
public sealed interface DomainEvent
        permits DomainEvent.TicketReserved,
        DomainEvent.TicketSold,
        DomainEvent.TicketReleased,
        DomainEvent.OrderConfirmed,
        DomainEvent.OrderFailed {

    /** Fired when a ticket is successfully moved to RESERVED state. */
    record TicketReserved(
            String ticketId,
            String orderId,
            String userId,
            String eventId,
            Instant reservedAt,
            Instant expiresAt
    ) implements DomainEvent {}

    /** Fired when a ticket is moved to SOLD (final state). */
    record TicketSold(
            String ticketId,
            String orderId,
            Instant soldAt
    ) implements DomainEvent {}

    /** Fired when a ticket is released back to AVAILABLE (expiry or payment failure). */
    record TicketReleased(
            String ticketId,
            String reason,
            Instant releasedAt
    ) implements DomainEvent {
        public static final String REASON_EXPIRED = "RESERVATION_EXPIRED";
        public static final String REASON_PAYMENT_FAILED = "PAYMENT_FAILED";
        public static final String REASON_COMPENSATION = "RESERVATION_COMPENSATION";
    }

    /** Fired when an order transitions to CONFIRMED (all tickets sold). */
    record OrderConfirmed(
            String orderId,
            List<String> ticketIds,
            Instant confirmedAt
    ) implements DomainEvent {}

    /** Fired when an order fails processing. */
    record OrderFailed(
            String orderId,
            String reason,
            Instant failedAt
    ) implements DomainEvent {}
}