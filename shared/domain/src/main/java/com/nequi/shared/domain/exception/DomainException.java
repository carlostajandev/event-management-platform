package com.nequi.shared.domain.exception;

/**
 * Abstract base for all domain exceptions.
 *
 * <p>Every exception carries an {@code errorCode} that is safe to expose to the
 * client (e.g. "EVENT_NOT_FOUND") without leaking infrastructure details.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
