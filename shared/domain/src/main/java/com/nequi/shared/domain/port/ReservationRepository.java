package com.nequi.shared.domain.port;

import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.model.ReservationStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Output port for Reservation persistence.
 *
 * <p>findExpiredReservations uses a GSI (status + expiresAt) — NOT a full table scan.
 * This is O(result) not O(table), critical for tables with millions of reservations.
 * The GSI: GSI1PK=STATUS#ACTIVE, SK=expiresAt (sorted) allows a KeyConditionExpression
 * with FilterExpression expiresAt < now().
 */
public interface ReservationRepository {

    Mono<Reservation> save(Reservation reservation);

    Mono<Reservation> findById(String reservationId);

    Flux<Reservation> findByEventId(String eventId);

    Flux<Reservation> findByUserId(String userId);

    /**
     * Finds expired ACTIVE reservations using GSI (status, expiresAt).
     * Used by the reconciliation fallback scheduler — NOT a table scan.
     */
    Flux<Reservation> findExpiredReservations(Instant before);

    Mono<Reservation> updateStatus(String reservationId, ReservationStatus newStatus);

    Mono<Void> delete(String reservationId);
}