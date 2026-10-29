package com.nequi.ticketing.domain.exception;

/** Input that can never be valid, regardless of the current state of the system. */
public final class DomainValidationException extends DomainException {

    public DomainValidationException(String message) {
        super(message);
    }
}
