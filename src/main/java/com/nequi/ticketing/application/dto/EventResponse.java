package com.nequi.ticketing.application.dto;

import com.nequi.ticketing.domain.model.Event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Output DTO for all Event use cases.
 *
 * <p>Decouples the HTTP response shape from the domain model.
 * The domain can evolve independently without breaking the API contract.
 */
public record EventResponse(
        String eventId,
        String name,
        String description,
        Instant eventDate,
        String venueName,
        String venueCity,
        String venueCountry,
        int totalCapacity,
        int availableTickets,
        BigDecimal ticketPrice,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Maps a domain Event to an EventResponse.
     * This mapping lives in the DTO — not in the domain entity.
     */
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.eventId().value(),
                event.name(),
                event.description(),
                event.eventDate(),
                event.venue().name(),
                event.venue().city(),
                event.venue().country(),
                event.totalCapacity(),
                event.availableTickets(),
                event.ticketPrice().amount(),
                event.ticketPrice().currency(),
                event.status().name(),
                event.createdAt(),
                event.updatedAt()
        );
    }
}
