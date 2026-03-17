package com.nequi.reservationservice.application.usecase;

import com.nequi.reservationservice.application.ReservationConstants;
import com.nequi.reservationservice.application.port.in.ReleaseExpiredReservationsUseCase;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.EventRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Application service implementing the release-expired-reservations use case.
 *
 * <p>This is a reconciliation fallback for environments where DynamoDB Streams
 * are not available (LocalStack, local dev). In production, TTL-triggered deletes
 * are captured by DynamoDB Streams and processed by the consumer-service.
 *
 * <p>Scalability design: uses a GSI query {@code STATUS#ACTIVE + expiresAt <= now}
 * which is O(expired results), NOT a full table scan. This prevents performance
 * degradation as the reservations table grows to millions of records.
 *
 * <p>Error isolation: each reservation release is processed independently via
 * {@code flatMap}. If one fails, processing continues for the remaining items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseExpiredReservationsService implements ReleaseExpiredReservationsUseCase {

    private final ReservationRepository reservationRepository;
    private final EventRepository       eventRepository;
    private final AuditRepository       auditRepository;
    private final MeterRegistry         meterRegistry;
    private final Clock                 clock;

    @Override
    public Mono<Integer> execute() {
        Instant now = Instant.now(clock);
        log.debug("Starting expired reservation release sweep at {}", now);

        return reservationRepository.findExpiredReservations(now)
                // Double-check status in memory (GSI may have eventual consistency lag)
                .filter(r -> r.status() == ReservationStatus.ACTIVE)
                // Process each reservation independently — errors don't abort the stream
                .flatMap(reservation ->
                        reservationRepository.updateStatus(reservation.id(), ReservationStatus.EXPIRED)
                                .flatMap(updated -> eventRepository.releaseTickets(reservation.eventId(), reservation.seatsCount()))
                                .flatMap(event -> auditRepository.save(AuditEntry.create(
                                        reservation.id(),
                                        ReservationConstants.AUDIT_ENTITY_TYPE,
                                        ReservationConstants.AUDIT_STATUS_ACTIVE,
                                        ReservationConstants.AUDIT_STATUS_EXPIRED,
                                        ReservationConstants.SYSTEM_USER,
                                        ReservationConstants.SCHEDULER_CORRELATION,
                                        now
                                )))
                                .doOnSuccess(audit ->
                                        log.debug("Released expired reservation: id={}, eventId={}, seatsCount={}",
                                                reservation.id(), reservation.eventId(), reservation.seatsCount()))
                                .onErrorResume(ex -> {
                                    log.error("Failed to release expired reservation: id={}, error={}",
                                            reservation.id(), ex.getMessage(), ex);
                                    return Mono.empty();
                                })
                )
                .count()
                .map(Long::intValue)
                .doOnSuccess(count -> {
                    if (count > 0) {
                        meterRegistry.counter(ReservationConstants.METRIC_RESERVATIONS_EXPIRED).increment(count);
                        log.info("Released {} expired reservations", count);
                    } else {
                        log.debug("No expired reservations to release");
                    }
                })
                .doOnError(ex ->
                        log.error("Error during expired reservation release sweep: {}", ex.getMessage(), ex));
    }
}
