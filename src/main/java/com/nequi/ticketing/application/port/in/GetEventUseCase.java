package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.EventResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Driving port for Event query use cases.
 */
public interface GetEventUseCase {

    /**
     * Returns a single event by its identifier.
     * Emits {@link com.nequi.ticketing.domain.exception.EventNotFoundException}
     * if the event does not exist.
     *
     * @param eventId the event identifier string
     * @return the event response
     */
    Mono<EventResponse> findById(String eventId);

    /**
     * Returns all available events.
     *
     * @return stream of all events
     */
    Flux<EventResponse> findAll();
}
