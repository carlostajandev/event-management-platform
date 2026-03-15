package com.nequi.ticketing.application.port.in;

import reactor.core.publisher.Mono;

/**
 * Driving port for releasing expired ticket reservations.
 */
public interface ReleaseExpiredReservationsUseCase {

    /**
     * Finds all RESERVED tickets past their TTL and releases them back to AVAILABLE.
     *
     * @return count of tickets released
     */
    Mono<Long> execute();
}
