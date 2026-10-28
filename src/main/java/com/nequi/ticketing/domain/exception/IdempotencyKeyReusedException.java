package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.IdempotencyKey;

/** Same idempotency key sent with a different request body. */
public final class IdempotencyKeyReusedException extends DomainException {

    public IdempotencyKeyReusedException(IdempotencyKey key) {
        super("Idempotency-Key %s was already used with a different request".formatted(key.value()));
    }
}
