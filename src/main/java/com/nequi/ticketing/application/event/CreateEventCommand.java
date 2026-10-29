package com.nequi.ticketing.application.event;

import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.Money;

import java.time.Instant;

public record CreateEventCommand(
        String name,
        String venue,
        Instant date,
        int capacity,
        Money ticketPrice,
        IdempotencyKey idempotencyKey,
        String requestHash) {
}
