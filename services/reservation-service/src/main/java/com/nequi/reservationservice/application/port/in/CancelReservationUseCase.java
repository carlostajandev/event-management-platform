package com.nequi.reservationservice.application.port.in;

import com.nequi.reservationservice.application.command.CancelReservationCommand;
import reactor.core.publisher.Mono;

/**
 * Input port for cancelling an active reservation.
 *
 * <p>Cancellation is only allowed when the reservation is in {@code ACTIVE} status
 * and the requesting user is the reservation owner. The cancelled seats are
 * atomically released back to the event's available inventory.
 */
public interface CancelReservationUseCase {

    /**
     * Cancels the reservation identified by the command's reservationId.
     *
     * @param command immutable command containing reservationId and userId
     * @return empty {@link Mono} on success, or error signal if not found / not authorized / wrong state
     */
    Mono<Void> execute(CancelReservationCommand command);
}
