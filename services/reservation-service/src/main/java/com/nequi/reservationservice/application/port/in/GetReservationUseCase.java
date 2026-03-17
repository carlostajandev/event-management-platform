package com.nequi.reservationservice.application.port.in;

import com.nequi.reservationservice.application.dto.ReservationResponse;
import reactor.core.publisher.Mono;

/**
 * Input port for retrieving a single reservation by its identifier.
 */
public interface GetReservationUseCase {

    /**
     * Finds a reservation by its unique identifier.
     *
     * @param reservationId the UUID of the reservation
     * @return {@link Mono} emitting the {@link ReservationResponse},
     *         or a {@link com.nequi.shared.domain.exception.ReservationNotFoundException}
     *         if no reservation exists with the given id
     */
    Mono<ReservationResponse> execute(String reservationId);
}
