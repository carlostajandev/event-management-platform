package com.nequi.ticketing.infrastructure.web;

/**
 * Classic token bucket: holds up to {@code burst} tokens and refills at {@code requestsPerSecond}. Time is passed
 * in by the caller (nanoseconds) so the bucket is deterministic under test.
 */
final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefill;

    TokenBucket(int requestsPerSecond, int burst, long now) {
        this.capacity = burst;
        this.tokensPerNano = requestsPerSecond / NANOS_PER_SECOND;
        this.tokens = burst;
        this.lastRefill = now;
    }

    synchronized boolean tryConsume(long now) {
        tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerNano);
        lastRefill = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }
}
