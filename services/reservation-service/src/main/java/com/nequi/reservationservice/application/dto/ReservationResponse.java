package com.nequi.reservationservice.application.dto;

import com.nequi.shared.domain.model.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound DTO for reservation responses.
 *
 * <p>Exposes only the fields needed by API consumers. Internal fields such as
 * {@code ttl} and {@code updatedAt} are intentionally excluded.
 *
 * <p>Monetary values use {@link BigDecimal} to avoid floating-point precision loss —
 * critical for a fintech platform where rounding errors cause real money discrepancies.
 */
public record ReservationResponse(
        String id,
        String eventId,
        String userId,
        int seatsCount,
        BigDecimal totalAmount,
        String currency,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt
) {}
