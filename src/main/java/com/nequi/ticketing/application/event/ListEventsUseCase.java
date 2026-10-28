package com.nequi.ticketing.application.event;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.port.EventRepository;
import reactor.core.publisher.Flux;

import java.time.Clock;

public final class ListEventsUseCase {

    public static final int MAX_LIMIT = 100;

    private final EventRepository events;
    private final Clock clock;

    public ListEventsUseCase(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** Upcoming events ordered by date; {@code limit} is clamped to 1..{@value #MAX_LIMIT}. */
    public Flux<Event> execute(int limit) {
        return events.findUpcoming(clock.instant(), Math.clamp(limit, 1, MAX_LIMIT));
    }
}
