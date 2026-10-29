package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

/**
 * Client-supplied key that makes a write safe to retry. Keys are scoped per caller, so two callers
 * can never collide or read each other's results.
 */
public record IdempotencyKey(String scope, String value) {

    private static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        if (scope == null || scope.isBlank()) {
            throw new DomainValidationException("Idempotency key scope is required");
        }
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new DomainValidationException("Idempotency-Key must be 1-%d characters".formatted(MAX_LENGTH));
        }
    }
}
