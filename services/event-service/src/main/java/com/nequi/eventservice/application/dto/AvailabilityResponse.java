package com.nequi.eventservice.application.dto;

/**
 * Outbound DTO for the get-availability endpoint.
 *
 * <p>Provides a lightweight snapshot of ticket availability without exposing
 * the full event model. Clients (e.g., the reservation-service) use {@code available}
 * as a soft pre-check before executing the atomic conditional DynamoDB write.
 *
 * @param eventId       UUID of the queried event
 * @param availableCount current number of available tickets
 * @param available     {@code true} when {@code availableCount > 0}
 */
public record AvailabilityResponse(
        String eventId,
        int availableCount,
        boolean available
) {}