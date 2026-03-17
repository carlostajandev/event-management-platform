package com.nequi.orderservice.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a new order from an existing ACTIVE reservation.
 *
 * <p>The idempotency key is supplied separately via the {@code X-Idempotency-Key} HTTP header.
 * This separates transport-layer concerns from the business request payload.
 */
public record CreateOrderRequest(
        @NotBlank(message = "reservationId must not be blank") String reservationId,
        @NotBlank(message = "userId must not be blank") String userId
) {}
