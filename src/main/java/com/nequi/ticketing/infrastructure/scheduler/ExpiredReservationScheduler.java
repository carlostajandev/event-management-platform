package com.nequi.ticketing.infrastructure.scheduler;

import com.nequi.ticketing.application.port.in.ReleaseExpiredReservationsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Triggers the expired reservation release job on a fixed schedule.
 *
 * <p>Uses {@link SchedulerLock} — only ONE ECS instance runs per cycle.
 *
 * <p><b>Why .block() here is correct (not an anti-pattern):</b>
 * {@code @Scheduled} methods run on a dedicated Spring scheduler thread,
 * not on Netty's event loop. Calling {@code .block()} on a scheduler thread
 * is safe and intentional — it guarantees the job completes before the next
 * {@code fixedDelay} cycle begins. Using {@code .subscribe()} instead would
 * return immediately, allowing a second scheduler trigger while the first
 * reactive chain is still executing (defeating ShedLock's purpose).
 *
 * <p>The timeout (50s) is lockAtMostFor (55s) minus 5s safety margin,
 * ensuring the reactive chain always terminates before the lock expires.
 */
@Component
@DependsOn("dynamoDbTableInitializer")
public class ExpiredReservationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredReservationScheduler.class);
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(50);

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    public ExpiredReservationScheduler(
            ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase) {
        this.releaseExpiredReservationsUseCase = releaseExpiredReservationsUseCase;
    }

    @Scheduled(fixedDelayString = "${ticketing.reservation.expiry-job-interval:60000}")
    @SchedulerLock(
            name = "releaseExpiredReservations",
            lockAtMostFor = "${shedlock.lock-at-most:PT55S}",
            lockAtLeastFor = "${shedlock.lock-at-least:PT30S}"
    )
    public void releaseExpiredReservations() {
        log.debug("Running expired reservation release job");

        // .block() is intentional here — see Javadoc above.
        // This runs on a Spring scheduler thread, not on Netty event loop.
        Long count = releaseExpiredReservationsUseCase.execute()
                .doOnError(ex -> log.error("Expiry job failed: {}", ex.getMessage()))
                .onErrorReturn(0L)
                .block(JOB_TIMEOUT);

        if (count != null && count > 0) {
            log.info("Expiry job completed. Released: {} tickets", count);
        } else {
            log.debug("Expiry job completed. No expired reservations.");
        }
    }
}