package com.nequi.reservationservice.application.port.in;

import com.nequi.reservationservice.application.command.ReserveTicketsCommand;
import com.nequi.reservationservice.application.dto.ReservationResponse;
import reactor.core.publisher.Mono;

/**
 * Input port for the reserve-tickets use case.
 *
 * <p>Drives the primary flow: validate event availability, atomically decrement
 * inventory, persist the reservation, and emit a domain event.
 */
public interface ReserveTicketsUseCase {

    /**
     * Executes the ticket reservation flow for the given command.
     *
     * @param command immutable command containing eventId, userId and seatsCount
     * @return {@link Mono} emitting the created {@link ReservationResponse}
     *         or an error signal on failure
     */
    Mono<ReservationResponse> execute(ReserveTicketsCommand command);
}
