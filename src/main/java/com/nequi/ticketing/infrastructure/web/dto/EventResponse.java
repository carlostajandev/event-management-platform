package com.nequi.ticketing.infrastructure.web.dto;

import com.nequi.ticketing.domain.model.Event;

import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
        String id,
        String name,
        String venue,
        Instant date,
        int capacity,
        BigDecimal ticketPrice,
        String currency,
        Instant createdAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.id().value(), event.name(), event.venue(), event.date(), event.capacity(),
                event.ticketPrice().amount(), event.ticketPrice().currency().getCurrencyCode(), event.createdAt());
    }
}
