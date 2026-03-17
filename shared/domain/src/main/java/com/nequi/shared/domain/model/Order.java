package com.nequi.shared.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order aggregate — Java 25 Record.
 *
 * <p>State machine (pattern matching enforced in OrderStateMachine):
 * <pre>
 *   PENDING_CONFIRMATION → CONFIRMED → COMPLETED
 *                        → FAILED    → (triggers reservation release)
 * </pre>
 *
 * <p>The idempotencyKey is the client-supplied header {@code Idempotency-Key}.
 * On duplicate request: the stored order response is returned without re-executing
 * business logic. Key is stored with a 24-hour TTL in emp-idempotency-keys table.
 */
public record Order(
        String id,
        String reservationId,
        String eventId,
        String userId,
        int seatsCount,
        BigDecimal totalAmount,
        String currency,
        OrderStatus status,
        String idempotencyKey,   // UUID supplied by client for deduplication
        Instant createdAt,
        Instant updatedAt
) {
    public Order confirm() {
        return withStatus(OrderStatus.CONFIRMED);
    }

    public Order fail() {
        return withStatus(OrderStatus.FAILED);
    }

    public Order complete() {
        return withStatus(OrderStatus.COMPLETED);
    }

    public boolean isFinal() {
        return status == OrderStatus.CONFIRMED
                || status == OrderStatus.COMPLETED
                || status == OrderStatus.FAILED;
    }

    private Order withStatus(OrderStatus newStatus) {
        return new Order(id, reservationId, eventId, userId, seatsCount, totalAmount, currency,
                newStatus, idempotencyKey, createdAt, Instant.now());
    }
}