package com.nequi.reservationservice.application.command;

/**
 * Immutable command for the reserve-tickets use case.
 *
 * <p>Rule: commands carry only the data the use case needs. No validation
 * annotations — those live on the HTTP request DTO and are enforced by
 * the handler layer before the command is created.
 */
public record ReserveTicketsCommand(
        String eventId,
        String userId,
        int seatsCount
) {}
