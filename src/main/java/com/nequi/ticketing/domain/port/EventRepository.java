package com.nequi.ticketing.domain.port;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Inventory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface EventRepository {

    /**
     * Stores the event, its initial inventory and the idempotency record atomically.
     * Fails with {@code DuplicateRequestException} if the idempotency key was already used.
     */
    Mono<Event> create(Event event, Inventory inventory, IdempotencyRecord idempotency);

    Mono<Event> findById(EventId id);

    /** Strongly consistent read of the counters, for real-time availability. */
    Mono<Inventory> findInventory(EventId id);

    Flux<Event> findUpcoming(Instant now, int limit);
}
