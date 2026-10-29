package com.nequi.ticketing.application.idempotency;

import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.exception.IdempotencyKeyReusedException;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.port.IdempotencyRepository;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs an idempotent write. A known key short-circuits to the original result without writing (double clicks,
 * client retries). The read is only an optimisation: the write itself stores the key with a condition, so two
 * concurrent requests with the same key still produce one resource, and the loser is answered as a replay.
 */
public final class IdempotentReplay {

    private final IdempotencyRepository idempotency;

    public IdempotentReplay(IdempotencyRepository idempotency) {
        this.idempotency = idempotency;
    }

    /**
     * @param write  performs the operation, storing the idempotency record in the same transaction
     * @param loader loads the resource created by the original request from its id
     */
    public <T> Mono<T> execute(IdempotencyKey key, String requestHash, Supplier<Mono<T>> write,
                               Function<String, Mono<T>> loader) {
        return idempotency.find(key)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> existing
                        .map(record -> replay(record, requestHash, loader))
                        .orElseGet(() -> Mono.defer(write)
                                .onErrorResume(DuplicateRequestException.class, duplicate -> idempotency.find(key)
                                        .switchIfEmpty(Mono.error(duplicate))
                                        .flatMap(record -> replay(record, requestHash, loader)))));
    }

    private static <T> Mono<T> replay(IdempotencyRecord record, String requestHash, Function<String, Mono<T>> loader) {
        if (!record.matches(requestHash)) {
            return Mono.error(new IdempotencyKeyReusedException(record.key()));
        }
        return loader.apply(record.resourceId());
    }
}
