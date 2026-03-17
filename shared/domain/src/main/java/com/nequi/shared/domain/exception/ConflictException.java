package com.nequi.shared.domain.exception;

/**
 * Base for concurrency and idempotency conflict exceptions → maps to HTTP 409.
 */
public abstract class ConflictException extends DomainException {

    protected ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }
}
