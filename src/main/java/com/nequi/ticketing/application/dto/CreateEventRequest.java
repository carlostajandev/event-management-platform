package com.nequi.ticketing.application.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Input DTO for the CreateEvent use case.
 *
 * <p>Validation annotations live here — not in the domain entity.
 * The domain validates business invariants; this DTO validates
 * that the HTTP request contains the required fields.
 */
public record CreateEventRequest(

        @NotBlank(message = "Event name is required")
        String name,

        String description,

        @NotNull(message = "Event date is required")
        @Future(message = "Event date must be in the future")
        Instant eventDate,

        @NotBlank(message = "Venue name is required")
        String venueName,

        @NotBlank(message = "Venue city is required")
        String venueCity,

        @NotBlank(message = "Venue country is required")
        String venueCountry,

        @NotNull(message = "Total capacity is required")
        @Positive(message = "Total capacity must be greater than zero")
        Integer totalCapacity,

        @NotNull(message = "Ticket price is required")
        @Positive(message = "Ticket price must be greater than zero")
        BigDecimal ticketPrice,

        @NotBlank(message = "Currency is required")
        String currency
) {}
