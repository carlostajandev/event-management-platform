package com.nequi.ticketing.infrastructure.scheduler;

import com.nequi.ticketing.application.port.in.ReleaseExpiredReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that triggers the expired reservation release job.
 *
 * <p>Runs every {@code ticketing.reservation.expiry-job-interval} milliseconds (default 60s).
 * Uses fixedDelay (not fixedRate) to avoid overlapping executions — the next
 * run starts only after the previous one completes.
 */
@Component
public class ExpiredReservationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredReservationScheduler.class);

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    public ExpiredReservationScheduler(
            ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase) {
        this.releaseExpiredReservationsUseCase = releaseExpiredReservationsUseCase;
    }

    @Scheduled(fixedDelayString = "${ticketing.reservation.expiry-job-interval:60000}")
    public void releaseExpiredReservations() {
        log.debug("Running expired reservation release job");

        releaseExpiredReservationsUseCase.execute()
                .subscribe(
                        count -> log.debug("Expiry job completed. Released: {}", count),
                        ex -> log.error("Expiry job failed: {}", ex.getMessage())
                );
    }
}
