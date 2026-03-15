package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.CreateEventRequest;
import com.nequi.ticketing.application.dto.EventResponse;
import reactor.core.publisher.Mono;

/**
 * Driving port for the CreateEvent use case.
 *
 * <p>Defines WHAT the application can do — not HOW.
 * The HTTP handler depends on this interface, never on the implementation.
 * This makes it trivial to swap implementations or mock in tests.
 */
public interface CreateEventUseCase {

    /**
     * Creates a new event and persists it in DRAFT status.
     *
     * @param request validated event creation request
     * @return the created event as a response DTO
     */
    Mono<EventResponse> execute(CreateEventRequest request);
}
