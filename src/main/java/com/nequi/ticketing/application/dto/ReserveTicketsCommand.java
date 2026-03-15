package com.nequi.ticketing.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Input command for the ReserveTickets use case.
 *
 * <p>Received via POST /api/v1/orders.
 * The idempotency key prevents duplicate reservations on network retries.
 */
public record ReserveTicketsCommand(

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "userId is required")
        String userId,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 10, message = "quantity cannot exceed 10")
        Integer quantity,

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {}
