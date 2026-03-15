package com.nequi.ticketing.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Business rule configuration for the ticketing platform.
 * Reads from application.yml under the "ticketing" prefix.
 */
@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(
        ReservationProperties reservation,
        IdempotencyProperties idempotency,
        PaginationProperties pagination,
        CorsProperties cors,
        int maxTicketsPerOrder
) {

    public record ReservationProperties(
            int ttlMinutes,
            long expiryJobInterval
    ) {}

    public record IdempotencyProperties(
            int ttlHours
    ) {}

    public record PaginationProperties(
            int defaultPageSize,
            int maxPageSize
    ) {}

    public record CorsProperties(
            List<String> allowedOrigins
    ) {}
}