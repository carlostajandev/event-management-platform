package com.nequi.shared.domain.event;

import java.time.Instant;

public record ReservationExpired(
        String aggregateId,   // reservationId
        String eventId,
        String userId,
        int seatsCount,
        Instant occurredAt
) implements DomainEvent {}