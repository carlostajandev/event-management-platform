package com.nequi.ticketing.domain.repository;

import com.nequi.ticketing.domain.valueobject.IdempotencyKey;
import reactor.core.publisher.Mono;

/**
 * Repository for idempotency key storage.
 *
 * <p>Stores request keys with their cached responses.
 * Keys expire after 24 hours (configured in application.yml).
 */
public interface IdempotencyRepository {

    /**
     * Checks if an idempotency key already exists.
     *
     * @param key the idempotency key to check
     * @return true if the key exists (duplicate request)
     */
    Mono<Boolean> exists(IdempotencyKey key);

    /**
     * Stores an idempotency key with its associated response JSON.
     *
     * @param key          the idempotency key
     * @param responseJson the serialized response to cache
     * @param ttlHours     how long to keep the key
     * @return empty Mono when saved
     */
    Mono<Void> save(IdempotencyKey key, String responseJson, int ttlHours);

    /**
     * Retrieves the cached response for an idempotency key.
     *
     * @param key the idempotency key
     * @return the cached response JSON, or empty if not found
     */
    Mono<String> findResponse(IdempotencyKey key);
}
