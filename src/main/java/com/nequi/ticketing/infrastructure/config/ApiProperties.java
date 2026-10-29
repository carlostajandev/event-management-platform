package com.nequi.ticketing.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Edge protection for the api role (ADR 0004: backpressure before DynamoDB).
 *
 * @param maxInFlightRequests API requests one instance processes at once; above it requests are rejected before
 *                            reaching DynamoDB. Sized with Little's law: target throughput x datastore latency.
 * @param perUser             token bucket per X-User-Id
 * @param perClientIp         token bucket per TCP peer address; 0 disables it (behind a load balancer the peer is
 *                            the balancer, and AWS WAF rate-based rules do this job)
 * @param maxTrackedClients   upper bound on buckets kept in memory, so a flood of new keys cannot exhaust the heap
 */
@Validated
@ConfigurationProperties("ticketing.api")
public record ApiProperties(
        @Min(1) int maxInFlightRequests,
        @Valid @NotNull RateLimit perUser,
        @Valid @NotNull RateLimit perClientIp,
        @Min(1) long maxTrackedClients) {

    /**
     * @param requestsPerSecond sustained rate; 0 disables the limit
     * @param burst             requests allowed at once after a quiet period
     */
    public record RateLimit(@Min(0) int requestsPerSecond, @Min(1) int burst) {

        public boolean enabled() {
            return requestsPerSecond > 0;
        }
    }
}
