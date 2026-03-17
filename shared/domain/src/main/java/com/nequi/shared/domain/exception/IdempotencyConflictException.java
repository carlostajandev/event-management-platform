package com.nequi.shared.domain.exception;

/**
 * Raised when an idempotency key is found but the request payload differs
 * from the original request — this is a client error (mismatch, not duplicate).
 */
public class IdempotencyConflictException extends ConflictException {

    public IdempotencyConflictException(String key) {
        super("IDEMPOTENCY_CONFLICT",
                "Idempotency key already used with different request payload: " + key);
    }
}
