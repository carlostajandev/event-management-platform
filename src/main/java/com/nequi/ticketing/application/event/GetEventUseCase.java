package com.nequi.ticketing.application.event;

import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.port.EventRepository;
import reactor.core.publisher.Mono;

public final class GetEventUseCase {

    private final EventRepository events;

    public GetEventUseCase(EventRepository events) {
        this.events = events;
    }

    public Mono<Event> execute(EventId id) {
        return events.findById(id).switchIfEmpty(Mono.error(() -> new NotFoundException("Event", id.value())));
    }
}
