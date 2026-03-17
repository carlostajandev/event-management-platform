package com.nequi.shared.domain.event;

import java.time.Instant;

public record OrderPlaced(
        String aggregateId,   // orderId
        String reservationId,
        String eventId,
        String userId,
        int seatsCount,
        Instant occurredAt
) implements DomainEvent {}