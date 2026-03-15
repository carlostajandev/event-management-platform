package com.nequi.ticketing.infrastructure.scheduler;

import com.nequi.ticketing.application.port.in.ReleaseExpiredReservationsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that triggers the expired reservation release job.
 *
 * <p>Uses {@link SchedulerLock} to ensure that in a multi-instance
 * deployment (e.g. 3 ECS tasks), only ONE instance runs the job per cycle.
 *
 * <p>Lock configuration:
 * <ul>
 *   <li>{@code lockAtMostFor} = 55s — forces lock release even if the instance crashes</li>
 *   <li>{@code lockAtLeastFor} = 30s — prevents immediate re-run by another instance</li>
 * </ul>
 *
 * <p>Uses {@code fixedDelay} (not {@code fixedRate}) to avoid overlapping
 * executions if the previous run takes longer than the interval.
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
    @SchedulerLock(
            name = "releaseExpiredReservations",
            lockAtMostFor = "${shedlock.lock-at-most:PT55S}",
            lockAtLeastFor = "${shedlock.lock-at-least:PT30S}"
    )
    public void releaseExpiredReservations() {
        log.debug("Running expired reservation release job");

        releaseExpiredReservationsUseCase.execute()
                .subscribe(
                        count -> {
                            if (count > 0) log.info("Expiry job completed. Released: {}", count);
                            else log.debug("Expiry job completed. No expired reservations.");
                        },
                        ex -> log.error("Expiry job failed: {}", ex.getMessage())
                );
    }
}