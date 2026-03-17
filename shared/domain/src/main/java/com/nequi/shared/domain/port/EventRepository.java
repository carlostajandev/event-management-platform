package com.nequi.shared.domain.port;

import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Output port for Event persistence — implemented in infrastructure layer.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>reserveTickets uses DynamoDB conditional write: {@code availableCount >= n AND version = expected}</li>
 *   <li>releaseTickets uses atomic ADD on availableCount (on reservation expiry)</li>
 *   <li>Both operations throw {@link com.nequi.shared.domain.exception.ConcurrentModificationException}
 *       on ConditionalCheckFailedException</li>
 * </ul>
 */
public interface EventRepository {

    Mono<Event> save(Event event);

    Mono<Event> findById(String eventId);

    Flux<Event> findByStatus(EventStatus status);

    /**
     * Atomically decrements availableCount by {@code seatsCount} using
     * DynamoDB conditional write with version check. Fails with
     * {@link com.nequi.shared.domain.exception.ConcurrentModificationException}
     * if version mismatch or insufficient tickets.
     */
    Mono<Event> reserveTickets(String eventId, int seatsCount, long expectedVersion);

    /**
     * Atomically increments availableCount by {@code seatsCount}
     * (called on reservation expiry or order failure).
     */
    Mono<Event> releaseTickets(String eventId, int seatsCount);

    Mono<Void> delete(String eventId);
}