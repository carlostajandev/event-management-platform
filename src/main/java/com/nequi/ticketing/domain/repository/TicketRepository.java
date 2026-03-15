package com.nequi.ticketing.domain.repository;

import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.TicketId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Domain repository interface for Ticket persistence.
 * All methods are reactive (Mono/Flux).
 */
public interface TicketRepository {

    Mono<Ticket> save(Ticket ticket);

    Mono<Ticket> findById(TicketId ticketId);

    /**
     * Finds tickets by event and status using the GSI.
     * Used for availability queries and expiry jobs.
     */
    Flux<Ticket> findByEventIdAndStatus(EventId eventId, TicketStatus status);

    /**
     * Finds all available tickets for an event.
     * Returns limited results for performance.
     */
    Flux<Ticket> findAvailableByEventId(EventId eventId, int limit);

    /**
     * Counts tickets by event and status.
     * Used for real-time availability endpoint.
     */
    Mono<Long> countByEventIdAndStatus(EventId eventId, TicketStatus status);

    /**
     * Finds all reserved tickets that have expired.
     * Used by the expiry job scheduler.
     */
    Flux<Ticket> findExpiredReservations(Instant before);

    /**
     * Updates a ticket using optimistic locking.
     * Fails with ConditionalCheckFailedException if version mismatch.
     */
    Mono<Ticket> update(Ticket ticket);

    /**
     * Saves multiple tickets in batch.
     * Used when creating ticket inventory for a new event.
     */
    Flux<Ticket> saveAll(Flux<Ticket> tickets);
}
