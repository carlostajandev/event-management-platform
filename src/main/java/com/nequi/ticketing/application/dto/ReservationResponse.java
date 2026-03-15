package com.nequi.ticketing.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response returned when tickets are successfully reserved.
 * Contains the orderId to track the purchase flow.
 */
public record ReservationResponse(
        String orderId,
        String eventId,
        String userId,
        int quantity,
        List<String> ticketIds,
        Instant reservedAt,
        Instant expiresAt,
        String status
) {}
