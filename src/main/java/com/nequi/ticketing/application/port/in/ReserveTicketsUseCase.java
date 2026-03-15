package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.ReservationResponse;
import com.nequi.ticketing.application.dto.ReserveTicketsCommand;
import reactor.core.publisher.Mono;

/**
 * Driving port for the ticket reservation use case.
 *
 * <p>Reserves tickets temporarily (10 minutes TTL) to prevent
 * overselling during concurrent purchase attempts.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>Idempotent — same idempotency key returns cached response</li>
 *   <li>Concurrency-safe — conditional writes prevent double-reservation</li>
 *   <li>Auto-release — expired reservations freed by scheduler</li>
 * </ul>
 */
public interface ReserveTicketsUseCase {

    /**
     * Reserves the requested number of tickets for an event.
     *
     * @param command reservation request with eventId, userId, quantity and idempotency key
     * @return reservation details including orderId and expiry time
     */
    Mono<ReservationResponse> execute(ReserveTicketsCommand command);
}
