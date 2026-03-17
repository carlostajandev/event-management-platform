package com.nequi.eventservice.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Venue value object embedded in {@link CreateEventRequest}.
 *
 * <p>Mirrors {@link com.nequi.shared.domain.model.Venue} but lives in the application layer
 * as a validated input DTO, decoupling the API contract from the domain model.
 */
public record VenueRequest(

        @NotBlank(message = "Venue name must not be blank")
        String name,

        @NotBlank(message = "Venue address must not be blank")
        String address,

        @NotBlank(message = "Venue city must not be blank")
        String city,

        @NotBlank(message = "Venue country must not be blank")
        String country,

        @Min(value = 1, message = "Venue capacity must be at least 1")
        int capacity

) {}