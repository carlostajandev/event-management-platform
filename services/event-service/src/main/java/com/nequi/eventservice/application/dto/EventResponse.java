package com.nequi.eventservice.application.dto;

import com.nequi.shared.domain.model.EventStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound DTO representing an Event in all API responses.
 *
 * <p>Decouples the API contract from the domain {@link com.nequi.shared.domain.model.Event}
 * record. If the domain model evolves, only the mapping logic in the use-case
 * service needs to change; client contracts remain stable.
 *
 * <p>Uses {@link VenueRequest} as the embedded venue representation,
 * keeping the API symmetrical for create and read operations.
 */
public record EventResponse(

        String id,
        String name,
        String description,
        VenueRequest venue,
        Instant eventDate,
        BigDecimal ticketPrice,
        String currency,
        int totalCapacity,
        int availableCount,
        EventStatus status,
        Instant createdAt,
        Instant updatedAt

) {}