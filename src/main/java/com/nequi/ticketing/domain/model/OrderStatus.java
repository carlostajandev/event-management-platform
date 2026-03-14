package com.nequi.ticketing.domain.model;

/**
 * Represents all possible states of a purchase order.
 *
 * <p>State transitions:
 * <pre>
 * PENDING   → PROCESSING  (consumer picks up from SQS)
 * PROCESSING → CONFIRMED  (payment successful, tickets SOLD)
 * PROCESSING → FAILED     (payment failed, tickets released)
 * PENDING   → CANCELLED   (user cancels before processing)
 * </pre>
 */
public enum OrderStatus {
    PENDING,
    PROCESSING,
    CONFIRMED,
    FAILED,
    CANCELLED;

    public boolean isFinal() {
        return this == CONFIRMED || this == FAILED || this == CANCELLED;
    }
}
