package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.IdempotencyKey;

/** The idempotency key was already used; the caller decides whether this is a replay or a conflict. */
public final class DuplicateRequestException extends DomainException {

    public DuplicateRequestException(IdempotencyKey key) {
        super("Idempotency-Key %s was already used".formatted(key.value()));
    }
}
