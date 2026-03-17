package com.nequi.shared.domain.event;

import java.time.Instant;

public record OrderConfirmed(
        String aggregateId,   // orderId
        String reservationId,
        String eventId,
        String userId,
        Instant occurredAt
) implements DomainEvent {}