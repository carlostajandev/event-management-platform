package com.nequi.reservationservice.application.usecase;

import com.nequi.reservationservice.application.ReservationConstants;
import com.nequi.reservationservice.application.command.CancelReservationCommand;
import com.nequi.reservationservice.application.port.in.CancelReservationUseCase;
import com.nequi.shared.domain.exception.ReservationNotFoundException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.EventRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Application service implementing the cancel-reservation use case.
 *
 * <p>Steps:
 * <ol>
 *   <li>Find reservation by id — {@link ReservationNotFoundException} if absent</li>
 *   <li>Validate that the requesting {@code userId} matches the reservation owner</li>
 *   <li>Validate reservation status is {@code ACTIVE}</li>
 *   <li>Transition status to {@code CANCELLED}</li>
 *   <li>Atomically release tickets back to event inventory (ADD expression)</li>
 *   <li>Persist audit entry {@code ACTIVE → CANCELLED}</li>
 *   <li>Increment Micrometer counter</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelReservationService implements CancelReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final EventRepository       eventRepository;
    private final AuditRepository       auditRepository;
    private final MeterRegistry         meterRegistry;
    private final Clock                 clock;

    @Override
    public Mono<Void> execute(CancelReservationCommand command) {
        String reservationId = command.reservationId();
        String userId = command.userId();
        String correlationId = MDC.get("correlationId") != null ? MDC.get("correlationId") : reservationId;
        Instant now = Instant.now(clock);

        return reservationRepository.findById(reservationId)
                // Step 1: Must exist
                .switchIfEmpty(Mono.error(new ReservationNotFoundException(reservationId)))
                // Step 2 & 3: Validate ownership and status
                .flatMap(reservation -> {
                    if (!reservation.userId().equals(userId)) {
                        return Mono.error(new IllegalArgumentException(
                                "User '%s' is not the owner of reservation '%s'".formatted(userId, reservationId)));
                    }
                    if (reservation.status() != ReservationStatus.ACTIVE) {
                        return Mono.error(new IllegalStateException(
                                "Cannot cancel reservation '%s' in status '%s'; only ACTIVE reservations can be cancelled"
                                        .formatted(reservationId, reservation.status())));
                    }
                    return Mono.just(reservation);
                })
                // Step 4: Update status to CANCELLED
                .flatMap(reservation ->
                        reservationRepository.updateStatus(reservationId, ReservationStatus.CANCELLED)
                                .thenReturn(reservation)
                )
                // Step 5: Release tickets back to event inventory (atomic ADD — no version check needed)
                .flatMap(reservation ->
                        eventRepository.releaseTickets(reservation.eventId(), reservation.seatsCount())
                                .thenReturn(reservation)
                )
                // Step 6: Write audit trail
                .flatMap(reservation ->
                        auditRepository.save(AuditEntry.create(
                                reservationId,
                                ReservationConstants.AUDIT_ENTITY_TYPE,
                                ReservationConstants.AUDIT_STATUS_ACTIVE,
                                ReservationConstants.AUDIT_STATUS_CANCELLED,
                                userId,
                                correlationId,
                                now
                        )).thenReturn(reservation)
                )
                // Step 7: Metric + logging
                .doOnSuccess(reservation -> {
                    meterRegistry.counter(ReservationConstants.METRIC_RESERVATIONS_CANCELLED).increment();
                    log.info("Reservation cancelled: reservationId={}, userId={}, eventId={}, seatsCount={}",
                            reservationId, userId, reservation.eventId(), reservation.seatsCount());
                })
                .doOnError(ReservationNotFoundException.class, ex ->
                        log.warn("Reservation not found for cancellation: reservationId={}", reservationId))
                .doOnError(IllegalArgumentException.class, ex ->
                        log.warn("Unauthorized cancellation attempt: reservationId={}, userId={}, reason={}",
                                reservationId, userId, ex.getMessage()))
                .doOnError(IllegalStateException.class, ex ->
                        log.warn("Invalid state for cancellation: reservationId={}, reason={}", reservationId, ex.getMessage()))
                .doOnError(ex -> {
                    if (!(ex instanceof ReservationNotFoundException)
                            && !(ex instanceof IllegalArgumentException)
                            && !(ex instanceof IllegalStateException)) {
                        log.error("Unexpected error during cancellation: reservationId={}, error={}",
                                reservationId, ex.getMessage(), ex);
                    }
                })
                .then();
    }
}
