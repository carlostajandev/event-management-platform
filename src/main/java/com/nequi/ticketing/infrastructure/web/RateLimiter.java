package com.nequi.ticketing.infrastructure.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nequi.ticketing.infrastructure.config.ApiProperties.RateLimit;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One token bucket per key, kept in a bounded cache. An idle key is forgotten after a minute, which is far longer
 * than any bucket needs to refill completely.
 */
final class RateLimiter {

    private final RateLimit limit;
    private final LongSupplier nanoTime;
    private final Cache<String, TokenBucket> buckets;

    RateLimiter(RateLimit limit, long maxKeys, LongSupplier nanoTime) {
        this.limit = limit;
        this.nanoTime = nanoTime;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxKeys)
                .expireAfterAccess(Duration.ofMinutes(1))
                .build();
    }

    boolean tryAcquire(String key) {
        if (!limit.enabled()) {
            return true;
        }
        long now = nanoTime.getAsLong();
        return buckets.get(key, k -> new TokenBucket(limit.requestsPerSecond(), limit.burst(), now)).tryConsume(now);
    }
}
