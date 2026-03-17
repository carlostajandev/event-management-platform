package com.nequi.reservationservice.application.usecase;

import com.nequi.reservationservice.application.ReservationConstants;
import com.nequi.reservationservice.application.command.ReserveTicketsCommand;
import com.nequi.reservationservice.application.dto.ReservationResponse;
import com.nequi.reservationservice.application.mapper.ReservationMapper;
import com.nequi.reservationservice.application.port.in.ReserveTicketsUseCase;
import com.nequi.shared.domain.exception.ConcurrentModificationException;
import com.nequi.shared.domain.exception.EventNotFoundException;
import com.nequi.shared.domain.exception.TicketNotAvailableException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.EventRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Application service implementing the reserve-tickets use case.
 *
 * <p>This is the most critical use case in the platform. It performs:
 * <ol>
 *   <li>Event lookup — fails fast with {@link EventNotFoundException} if not found</li>
 *   <li>Availability check — fails fast with {@link TicketNotAvailableException} if insufficient</li>
 *   <li>Atomic inventory decrement via DynamoDB conditional write with optimistic locking</li>
 *   <li>Exponential backoff retry (up to 3 attempts) on {@link ConcurrentModificationException}</li>
 *   <li>Reservation persistence</li>
 *   <li>Audit trail write</li>
 *   <li>Micrometer counter increment</li>
 * </ol>
 *
 * <p>Retry strategy: {@code Retry.backoff(3, 100ms)} doubles the delay on each attempt
 * (100ms → 200ms → 400ms). After exhaustion, the {@link ConcurrentModificationException}
 * propagates as HTTP 409 Conflict via the global error handler.
 *
 * <p>Total amount calculation uses {@link BigDecimal} multiplication to preserve
 * monetary precision — floating-point arithmetic is never used for money.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReserveTicketsService implements ReserveTicketsUseCase {

    private final EventRepository       eventRepository;
    private final ReservationRepository reservationRepository;
    private final AuditRepository       auditRepository;
    private final ReservationMapper     reservationMapper;
    private final MeterRegistry         meterRegistry;
    private final Clock                 clock;

    @Override
    public Mono<ReservationResponse> execute(ReserveTicketsCommand request) {
        String correlationId = MDC.get("correlationId") != null ? MDC.get("correlationId") : UUID.randomUUID().toString();
        Instant now = Instant.now(clock);

        return eventRepository.findById(request.eventId())
                // Step 1: Event must exist
                .switchIfEmpty(Mono.error(new EventNotFoundException(request.eventId())))
                // Step 2: Check availability (in-memory guard before hitting DynamoDB conditionally)
                .flatMap(event -> {
                    if (!event.hasAvailableTickets(request.seatsCount())) {
                        return Mono.error(new TicketNotAvailableException(
                                request.eventId(), request.seatsCount(), event.availableCount()));
                    }
                    // Step 3: Atomic conditional decrement with optimistic locking.
                    // Mono.defer() is required so retryWhen re-invokes the actual method
                    // on each retry attempt (not just re-subscribes to the same Mono instance).
                    return Mono.defer(() -> eventRepository.reserveTickets(request.eventId(), request.seatsCount(), event.version()))
                            .retryWhen(Retry.backoff(ReservationConstants.MAX_RETRY_ATTEMPTS, Duration.ofMillis(ReservationConstants.RETRY_BACKOFF_MILLIS))
                                    .filter(e -> e instanceof ConcurrentModificationException)
                                    .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                            .map(updatedEvent -> {
                                // Step 4: Calculate total amount
                                BigDecimal totalAmount = updatedEvent.ticketPrice()
                                        .multiply(BigDecimal.valueOf(request.seatsCount()));
                                return Reservation.create(
                                        UUID.randomUUID().toString(),
                                        request.eventId(),
                                        request.userId(),
                                        request.seatsCount(),
                                        totalAmount,
                                        updatedEvent.currency(),
                                        now
                                );
                            });
                })
                // Step 5: Persist the reservation
                .flatMap(reservation -> reservationRepository.save(reservation)
                        // Step 6: Write audit trail
                        .flatMap(savedReservation ->
                                auditRepository.save(AuditEntry.create(
                                        savedReservation.id(),
                                        ReservationConstants.AUDIT_ENTITY_TYPE,
                                        ReservationConstants.AUDIT_STATUS_NONE,
                                        ReservationConstants.AUDIT_STATUS_ACTIVE,
                                        request.userId(),
                                        correlationId,
                                        now
                                )).thenReturn(savedReservation)
                        )
                )
                // Step 7: Emit metric and map to response
                .map(savedReservation -> {
                    meterRegistry.counter(ReservationConstants.METRIC_TICKETS_RESERVED).increment();
                    MDC.put("reservationId", savedReservation.id());
                    MDC.put("eventId", savedReservation.eventId());
                    log.info("Tickets reserved: reservationId={}, eventId={}, seatsCount={}, userId={}",
                            savedReservation.id(), savedReservation.eventId(),
                            savedReservation.seatsCount(), savedReservation.userId());
                    MDC.remove("reservationId");
                    MDC.remove("eventId");
                    return reservationMapper.toResponse(savedReservation);
                })
                .doOnError(EventNotFoundException.class, ex ->
                        log.warn("Event not found during reservation: eventId={}", request.eventId()))
                .doOnError(TicketNotAvailableException.class, ex ->
                        log.warn("Insufficient tickets: eventId={}, requested={}", request.eventId(), request.seatsCount()))
                .doOnError(ConcurrentModificationException.class, ex ->
                        log.error("Concurrent modification exhausted retries: eventId={}", request.eventId()))
                .doOnError(ex -> {
                    if (!(ex instanceof EventNotFoundException)
                            && !(ex instanceof TicketNotAvailableException)
                            && !(ex instanceof ConcurrentModificationException)) {
                        log.error("Unexpected error during ticket reservation: eventId={}, error={}",
                                request.eventId(), ex.getMessage(), ex);
                    }
                });
    }
}
