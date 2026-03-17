package com.nequi.reservationservice.application.command;

/**
 * Immutable command for the cancel-reservation use case.
 */
public record CancelReservationCommand(
        String reservationId,
        String userId
) {}
