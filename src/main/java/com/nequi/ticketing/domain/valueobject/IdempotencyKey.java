package com.nequi.ticketing.domain.valueobject;

/**
 * Strongly-typed idempotency key for purchase requests.
 *
 * <p>Clients must send this key in the X-Idempotency-Key header.
 * If the same key is used twice, the second request returns the
 * cached response without reprocessing — prevents duplicate charges
 * on network retries.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey cannot be null or blank");
        }
        if (value.length() > 255) {
            throw new IllegalArgumentException("IdempotencyKey cannot exceed 255 characters");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
