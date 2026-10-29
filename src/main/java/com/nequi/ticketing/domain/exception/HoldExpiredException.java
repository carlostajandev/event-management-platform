package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.model.OrderId;

import java.time.Instant;

public final class HoldExpiredException extends DomainException {

    public HoldExpiredException(OrderId orderId, Instant expiresAt) {
        super("Hold for order %s expired at %s".formatted(orderId.value(), expiresAt));
    }
}
