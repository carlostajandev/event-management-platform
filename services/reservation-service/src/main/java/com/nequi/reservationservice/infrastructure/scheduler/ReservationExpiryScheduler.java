package com.nequi.reservationservice.infrastructure.scheduler;

import com.nequi.reservationservice.application.port.in.ReleaseExpiredReservationsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled reconciliation job that releases expired reservations.
 *
 * <p>This is a fallback for environments where DynamoDB Streams are not available
 * (LocalStack, local dev). In production, the primary mechanism is:
 * DynamoDB TTL delete → DynamoDB Streams → consumer-service Lambda/listener → releaseTickets.
 *
 * <p>Runs every 60 seconds with {@code fixedDelay} (delay after completion, not rate).
 * This prevents scheduler cycle overlap if a sweep takes longer than 60 seconds under load.
 *
 * <p>Disabled by default via {@code scheduler.reservation-expiry.enabled=false} in production
 * (where DynamoDB Streams handle expiry). Enabled in local/test via application.yml.
 *
 * <p>Non-blocking: uses {@code subscribe()} fire-and-forget because the scheduler thread
 * is independent and the reactive pipeline already handles errors internally.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.reservation-expiry.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationExpiryScheduler {

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    /**
     * Sweeps for expired ACTIVE reservations and releases their ticket inventory.
     *
     * <p>Uses {@code fixedDelay} (not {@code fixedRate}) to prevent concurrent executions
     * when a sweep takes longer than the interval — critical under high load.
     *
     * <p>Fire-and-forget {@code subscribe()} is appropriate here: the scheduler has
     * its own dedicated thread and the reactive pipeline manages its own error handling.
     * The {@code doOnSuccess}/{@code doOnError} callbacks inside the use case ensure
     * observability without blocking the scheduler thread.
     */
    @Scheduled(fixedDelay = 60_000)
    public void releaseExpiredReservations() {
        log.debug("Reservation expiry scheduler triggered");
        releaseExpiredReservationsUseCase.execute()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Scheduler released {} expired reservations", count);
                    }
                })
                .doOnError(ex ->
                        log.error("Scheduler failed to release expired reservations: {}", ex.getMessage(), ex))
                .subscribe();  // fire-and-forget: scheduler manages its own thread lifecycle
    }
}
