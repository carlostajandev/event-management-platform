package com.nequi.eventservice.application.port.in;

import com.nequi.eventservice.application.dto.EventResponse;
import reactor.core.publisher.Mono;

/**
 * Input port — retrieves a single Event by its identifier.
 *
 * <p>Emits {@link com.nequi.shared.domain.exception.EventNotFoundException}
 * when no event with the given id exists, allowing the global error handler
 * to translate it to HTTP 404.
 */
public interface GetEventUseCase {

    /**
     * Retrieves the event identified by {@code eventId}.
     *
     * @param eventId UUID string identifying the event
     * @return {@link Mono} emitting the {@link EventResponse}, or
     *         {@link Mono#error} with {@link com.nequi.shared.domain.exception.EventNotFoundException}
     */
    Mono<EventResponse> execute(String eventId);
}