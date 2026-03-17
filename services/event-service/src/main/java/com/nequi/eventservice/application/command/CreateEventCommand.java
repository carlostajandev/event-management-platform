package com.nequi.eventservice.application.command;

import com.nequi.shared.domain.model.Venue;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable command for the create-event use case.
 *
 * <p>Uses the domain {@link Venue} value object directly — no validation
 * annotations, those are enforced at the HTTP boundary before mapping.
 */
public record CreateEventCommand(
        String name,
        String description,
        Venue venue,
        Instant eventDate,
        BigDecimal ticketPrice,
        String currency,
        int totalCapacity
) {}
