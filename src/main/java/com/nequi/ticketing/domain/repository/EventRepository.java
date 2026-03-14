package com.nequi.ticketing.domain.repository;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.valueobject.EventId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Domain repository interface for Event persistence.
 *
 * <p>Defined in the domain layer — the domain declares WHAT it needs,
 * infrastructure decides HOW to implement it (DynamoDB, in-memory, etc).
 *
 * <p>All methods return reactive types (Mono/Flux) so the domain
 * stays compatible with the non-blocking WebFlux stack without
 * importing any Spring or AWS dependencies.
 */
public interface EventRepository {

    /**
     * Persists a new event.
     * Fails with a duplicate key error if the eventId already exists.
     *
     * @param event the event to save
     * @return the saved event
     */
    Mono<Event> save(Event event);

    /**
     * Finds an event by its identifier.
     *
     * @param eventId the event identifier
     * @return the event if found, or empty Mono if not
     */
    Mono<Event> findById(EventId eventId);

    /**
     * Returns all events.
     * In production, this would include pagination parameters.
     *
     * @return stream of all events
     */
    Flux<Event> findAll();

    /**
     * Updates an existing event using optimistic locking.
     * The implementation must use a conditional write that checks
     * the current version matches before updating.
     *
     * @param event the event with updated fields and incremented version
     * @return the updated event
     */
    Mono<Event> update(Event event);

    /**
     * Checks if an event exists by its identifier.
     *
     * @param eventId the event identifier
     * @return true if the event exists
     */
    Mono<Boolean> existsById(EventId eventId);
}
