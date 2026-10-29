package com.nequi.ticketing.application.order;

import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.UserId;

/** Also used for complimentary tickets, where {@code userId} is the recipient. */
public record PlaceOrderCommand(
        EventId eventId,
        UserId userId,
        int quantity,
        IdempotencyKey idempotencyKey,
        String requestHash) {
}
