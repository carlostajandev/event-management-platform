package com.nequi.ticketing.infrastructure.scheduling;

import com.nequi.ticketing.application.order.ReleaseExpiredHoldsUseCase;
import com.nequi.ticketing.infrastructure.config.WorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** RF6: periodically returns tickets of expired holds to the available pool. */
@Profile("worker")
@Component
class ExpiredHoldsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredHoldsJob.class);

    private final ReleaseExpiredHoldsUseCase releaseExpiredHolds;
    private final WorkerProperties properties;
    private final MeterRegistry meters;

    ExpiredHoldsJob(ReleaseExpiredHoldsUseCase releaseExpiredHolds, WorkerProperties properties, MeterRegistry meters) {
        this.releaseExpiredHolds = releaseExpiredHolds;
        this.properties = properties;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${ticketing.worker.expiry-interval}")
    Mono<Void> releaseExpired() {
        return releaseExpiredHolds.execute(properties.expiryBatchSize())
                .doOnNext(released -> {
                    if (released > 0) {
                        meters.counter("ticketing.holds.released").increment(released);
                        log.info("Released {} expired holds", released);
                    }
                })
                .onErrorResume(error -> {
                    log.warn("Expiry job run failed, retrying next run: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
