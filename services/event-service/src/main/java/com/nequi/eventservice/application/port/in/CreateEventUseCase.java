package com.nequi.eventservice.application.port.in;

import com.nequi.eventservice.application.command.CreateEventCommand;
import com.nequi.eventservice.application.dto.EventResponse;
import reactor.core.publisher.Mono;

/**
 * Input port — defines the contract for creating a new Event.
 *
 * <p>Follows Clean Architecture: the application layer defines this interface;
 * the use-case implementation lives in the same layer; adapters (web) call it
 * without depending on infrastructure details.
 */
public interface CreateEventUseCase {

    /**
     * Creates a new event from the given command.
     *
     * @param command immutable command carrying all event attributes
     * @return {@link Mono} emitting the persisted {@link EventResponse}
     */
    Mono<EventResponse> execute(CreateEventCommand command);
}