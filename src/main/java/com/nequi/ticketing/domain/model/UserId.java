package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

public record UserId(String value) {

    private static final int MAX_LENGTH = 64;

    public UserId {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new DomainValidationException("UserId must be 1-%d non-blank characters".formatted(MAX_LENGTH));
        }
    }
}
