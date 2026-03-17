package com.nequi.eventservice.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inbound DTO for the create-event use case.
 *
 * <p>Uses Bean Validation annotations; the web handler validates this record
 * before delegating to {@link com.nequi.eventservice.application.port.in.CreateEventUseCase}.
 * Validation errors are surfaced as HTTP 400 by the global error handler.
 *
 * <p>Java 25 Record: immutable by design, no boilerplate getters/setters.
 */
public record CreateEventRequest(

        @NotBlank(message = "Event name must not be blank")
        String name,

        @NotBlank(message = "Event description must not be blank")
        String description,

        @NotNull(message = "Venue must not be null")
        @Valid
        VenueRequest venue,

        @NotNull(message = "Event date must not be null")
        @Future(message = "Event date must be in the future")
        Instant eventDate,

        @NotNull(message = "Ticket price must not be null")
        @DecimalMin(value = "0.01", message = "Ticket price must be at least 0.01")
        BigDecimal ticketPrice,

        @NotBlank(message = "Currency must not be blank")
        @Size(min = 3, max = 3, message = "Currency must be an ISO 4217 3-letter code")
        String currency,

        @Min(value = 1, message = "Total capacity must be at least 1")
        @Max(value = 100_000, message = "Total capacity must not exceed 100 000")
        int totalCapacity

) {}