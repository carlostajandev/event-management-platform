package com.nequi.reservationservice.application.port.in;

import reactor.core.publisher.Mono;

/**
 * Input port for the reconciliation scheduler that releases expired reservations.
 *
 * <p>Primary mechanism: DynamoDB TTL + Streams (handled by consumer-service).
 * This use case is a fallback for local/test environments where DynamoDB Streams
 * are not available (e.g., LocalStack). It queries via GSI — O(results), not O(table).
 */
public interface ReleaseExpiredReservationsUseCase {

    /**
     * Finds all ACTIVE reservations past their {@code expiresAt} time,
     * transitions them to {@code EXPIRED}, and releases their ticket count
     * back to the event's available inventory.
     *
     * @return {@link Mono} emitting the count of reservations released
     */
    Mono<Integer> execute();
}
