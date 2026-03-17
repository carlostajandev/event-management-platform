package com.nequi.reservationservice.application.mapper;

import com.nequi.reservationservice.application.dto.ReservationResponse;
import com.nequi.shared.domain.model.Reservation;
import org.springframework.stereotype.Component;

/**
 * Maps between {@link Reservation} domain model and reservation-service DTOs.
 *
 * <p>Rule: no use case maps inline. All conversions go through this class.
 * <ul>
 *   <li>{@link #toResponse} — outbound: domain model → response DTO</li>
 * </ul>
 */
@Component
public class ReservationMapper {

    public ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.id(),
                reservation.eventId(),
                reservation.userId(),
                reservation.seatsCount(),
                reservation.totalAmount(),
                reservation.currency(),
                reservation.status(),
                reservation.expiresAt(),
                reservation.createdAt()
        );
    }
}
