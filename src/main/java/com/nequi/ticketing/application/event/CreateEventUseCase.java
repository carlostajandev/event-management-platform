package com.nequi.ticketing.application.event;

import com.nequi.ticketing.application.idempotency.IdempotentReplay;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.port.EventRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

public final class CreateEventUseCase {

    private final EventRepository events;
    private final IdempotentReplay idempotentReplay;
    private final Clock clock;

    public CreateEventUseCase(EventRepository events, IdempotentReplay idempotentReplay, Clock clock) {
        this.events = events;
        this.idempotentReplay = idempotentReplay;
        this.clock = clock;
    }

    public Mono<Event> execute(CreateEventCommand command) {
        return idempotentReplay.execute(command.idempotencyKey(), command.requestHash(),
                () -> create(command),
                id -> events.findById(new EventId(id)).switchIfEmpty(Mono.error(new NotFoundException("Event", id))));
    }

    private Mono<Event> create(CreateEventCommand command) {
        Instant now = clock.instant();
        Event event = Event.create(EventId.random(), command.name(), command.venue(), command.date(),
                command.capacity(), command.ticketPrice(), now);
        IdempotencyRecord idempotency = new IdempotencyRecord(command.idempotencyKey(), command.requestHash(),
                event.id().value(), now);
        return events.create(event, Inventory.initial(event.id(), event.capacity()), idempotency);
    }
}
