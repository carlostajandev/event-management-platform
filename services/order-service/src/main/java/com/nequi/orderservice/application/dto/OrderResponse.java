package com.nequi.orderservice.application.dto;

import com.nequi.shared.domain.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO returned after order creation or status lookup.
 *
 * <p>This record is also serialized to JSON and stored in the idempotency cache
 * (emp-idempotency-keys). On duplicate requests with the same {@code X-Idempotency-Key},
 * the cached JSON is deserialized and returned immediately without re-executing business logic.
 */
public record OrderResponse(
        String id,
        String reservationId,
        String eventId,
        String userId,
        int seatsCount,
        BigDecimal totalAmount,
        String currency,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt
) {}
