package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

import java.util.UUID;

public record EventId(String value) {

    private static final int MAX_LENGTH = 64;

    public EventId {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new DomainValidationException("EventId must be 1-%d non-blank characters".formatted(MAX_LENGTH));
        }
    }

    public static EventId random() {
        return new EventId(UUID.randomUUID().toString());
    }
}
