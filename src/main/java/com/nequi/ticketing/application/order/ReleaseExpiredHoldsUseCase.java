package com.nequi.ticketing.application.order;

import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.port.OrderRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * RF6: returns tickets of expired holds to AVAILABLE. Each release is conditional on the order still being in
 * the state that was read, so an order sold or released concurrently is simply skipped.
 */
public final class ReleaseExpiredHoldsUseCase {

    private static final int CONCURRENCY = 8;

    private final OrderRepository orders;
    private final Clock clock;

    public ReleaseExpiredHoldsUseCase(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Reads the clock on every subscription: a scheduler may subscribe to the same Mono on each run.
     *
     * @return how many orders were released
     */
    public Mono<Long> execute(int batchSize) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return orders.findExpiredHolds(now, batchSize)
                    .flatMap(order -> orders.apply(order.release(now))
                            .onErrorResume(OrderStateChangedException.class, changed -> Mono.empty()), CONCURRENCY)
                    .count();
        });
    }
}
