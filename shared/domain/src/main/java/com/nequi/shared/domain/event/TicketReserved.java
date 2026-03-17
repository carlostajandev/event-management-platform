package com.nequi.shared.domain.event;

import java.time.Instant;

public record TicketReserved(
        String aggregateId,   // reservationId
        String eventId,
        String userId,
        int seatsCount,
        Instant expiresAt,
        Instant occurredAt
) implements DomainEvent {}