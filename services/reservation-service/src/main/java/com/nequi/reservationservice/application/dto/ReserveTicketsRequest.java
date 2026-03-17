package com.nequi.reservationservice.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Inbound DTO for the reserve-tickets use case.
 *
 * <p>Bean Validation constraints are enforced by the handler layer before
 * delegating to the application service. This keeps domain logic clean of
 * HTTP-layer concerns.
 */
public record ReserveTicketsRequest(
        @NotBlank(message = "eventId must not be blank") String eventId,
        @NotBlank(message = "userId must not be blank") String userId,
        @Min(value = 1, message = "seatsCount must be at least 1")
        @Max(value = 10, message = "seatsCount must not exceed 10")
        int seatsCount
) {}
