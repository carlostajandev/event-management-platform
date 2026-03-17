package com.nequi.shared.domain.exception;

public class ReservationNotFoundException extends NotFoundException {

    public ReservationNotFoundException(String reservationId) {
        super("RESERVATION_NOT_FOUND", "Reservation not found: " + reservationId);
    }
}
