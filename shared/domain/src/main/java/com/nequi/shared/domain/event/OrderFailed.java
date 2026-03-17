package com.nequi.shared.domain.event;

import java.time.Instant;

public record OrderFailed(
        String aggregateId,   // orderId
        String reservationId,
        String eventId,
        String userId,
        String reason,
        Instant occurredAt
) implements DomainEvent {}