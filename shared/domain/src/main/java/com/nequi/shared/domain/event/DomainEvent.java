package com.nequi.shared.domain.event;

import java.time.Instant;

/**
 * Sealed interface for domain events — Java 25 pattern.
 *
 * <p>Permits only these concrete subtypes, enabling exhaustive pattern matching:
 * <pre>{@code
 *   String topic = switch (event) {
 *       case TicketReserved e  -> "reservations";
 *       case OrderPlaced e     -> "orders";
 *       case ReservationExpired e -> "expirations";
 *   };
 * }</pre>
 */
public sealed interface DomainEvent
        permits TicketReserved, OrderPlaced, ReservationExpired, OrderConfirmed, OrderFailed {

    String aggregateId();
    Instant occurredAt();
}