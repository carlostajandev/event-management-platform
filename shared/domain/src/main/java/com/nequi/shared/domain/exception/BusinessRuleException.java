package com.nequi.shared.domain.exception;

/**
 * Base for domain business-rule violations → maps to HTTP 422 or 409 depending on subtype.
 */
public abstract class BusinessRuleException extends DomainException {

    protected BusinessRuleException(String errorCode, String message) {
        super(errorCode, message);
    }
}
