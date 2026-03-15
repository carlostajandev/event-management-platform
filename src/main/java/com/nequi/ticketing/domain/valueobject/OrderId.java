package com.nequi.ticketing.domain.valueobject;

import java.util.UUID;

/**
 * Strongly-typed identifier for a purchase Order.
 */
public record OrderId(String value) {

    public OrderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OrderId cannot be null or blank");
        }
    }

    public static OrderId generate() {
        return new OrderId("ord_" + UUID.randomUUID());
    }

    public static OrderId of(String value) {
        return new OrderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
