package com.nequi.ticketing.application.dto;

/**
 * Real-time availability response for an event.
 */
public record AvailabilityResponse(
        String eventId,
        long availableTickets,
        long reservedTickets,
        long soldTickets,
        long totalCapacity,
        boolean isAvailable
) {}
