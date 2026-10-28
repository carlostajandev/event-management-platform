package com.nequi.ticketing.application.event;

import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.port.EventRepository;
import reactor.core.publisher.Mono;

/** Real-time availability (RF7): a strongly consistent read of the per-state counters. */
public final class GetAvailabilityUseCase {

    private final EventRepository events;

    public GetAvailabilityUseCase(EventRepository events) {
        this.events = events;
    }

    public Mono<Inventory> execute(EventId id) {
        return events.findInventory(id).switchIfEmpty(Mono.error(() -> new NotFoundException("Event", id.value())));
    }
}
