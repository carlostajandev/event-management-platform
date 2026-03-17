package com.nequi.shared.domain.exception;

/**
 * Base for all "resource not found" domain exceptions → maps to HTTP 404.
 */
public abstract class NotFoundException extends DomainException {

    protected NotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }
}
