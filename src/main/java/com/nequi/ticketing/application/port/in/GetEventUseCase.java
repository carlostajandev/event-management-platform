package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.application.dto.PagedResponse;
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
     * Returns all available events up to the configured default page size.
     *
     * @return stream of all events
     */
    Flux<EventResponse> findAll();

    /**
     * Returns a paginated response with metadata.
     * Uses offset-based pagination via reactive skip/take.
     * In production, replace with cursor-based pagination
     * using DynamoDB's lastEvaluatedKey for O(1) access.
     *
     * @param page zero-based page number
     * @param size maximum number of items per page (capped at maxPageSize)
     * @return paged response with items and pagination metadata
     */
    Mono<PagedResponse<EventResponse>> findPaged(int page, int size);
}