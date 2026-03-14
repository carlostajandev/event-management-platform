package com.nequi.ticketing.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business rule configuration for the ticketing platform.
 * Reads from application.yml under the "ticketing" prefix.
 */
@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        ReservationProperties reservation,
        IdempotencyProperties idempotency,
        int maxTicketsPerOrder
) {

    public record ReservationProperties(
            int ttlMinutes,
            long expiryJobInterval
    ) {}

    public record IdempotencyProperties(
            int ttlHours
    ) {}
}
