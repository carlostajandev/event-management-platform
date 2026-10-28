package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

import java.util.UUID;

public record OrderId(String value) {

    private static final int MAX_LENGTH = 64;

    public OrderId {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new DomainValidationException("OrderId must be 1-%d non-blank characters".formatted(MAX_LENGTH));
        }
    }

    public static OrderId random() {
        return new OrderId(UUID.randomUUID().toString());
    }
}
