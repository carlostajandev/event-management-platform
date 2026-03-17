package com.nequi.consumerservice.infrastructure.scheduler;

import com.nequi.consumerservice.application.usecase.OutboxPollerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger for the Outbox Pattern poller.
 *
 * <p>Invokes {@link OutboxPollerService#pollAndPublish()} with {@code fixedDelay = 5000ms}.
 * {@code fixedDelay} (not {@code fixedRate}) means the 5-second delay is measured from the
 * completion of the previous poll cycle — preventing overlapping cycles under high load.
 *
 * <p>Error handling: {@code doOnError} logs failures but the {@code subscribe()} call
 * does not propagate exceptions to the scheduler thread, ensuring the scheduler continues
 * running even if a poll cycle fails.
 *
 * <p>{@link EnableScheduling} is placed here rather than the main application class
 * to keep scheduling concerns co-located with the scheduler component.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxPollerService outboxPollerService;

    /**
     * Triggers a poll cycle every 5 seconds after the previous cycle completes.
     *
     * <p>The reactive pipeline is subscribed here — the scheduler thread is released
     * immediately. The actual work happens on the reactor scheduler thread pool.
     */
    @Scheduled(fixedDelay = 5_000)
    public void poll() {
        log.debug("Starting outbox poll cycle");
        outboxPollerService.pollAndPublish()
                .doOnError(e -> log.error("Outbox poll cycle failed: {}", e.getMessage(), e))
                .subscribe(
                        null,
                        e -> log.error("Unhandled error in outbox poll subscription: {}", e.getMessage(), e),
                        () -> log.debug("Outbox poll cycle completed")
                );
    }
}
