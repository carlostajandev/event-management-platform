package com.nequi.ticketing.domain.exception;

/**
 * Base type for business rule violations. Sealed so the web layer can map every case exhaustively.
 */
public abstract sealed class DomainException extends RuntimeException
        permits DomainValidationException, InsufficientTicketsException, IllegalTransitionException,
                HoldExpiredException {

    protected DomainException(String message) {
        super(message);
    }
}
