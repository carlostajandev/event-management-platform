package com.nequi.ticketing.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param ordersQueue        queue the outbox relay publishes placed orders to and the consumer listens on
 * @param outboxPollInterval delay between outbox relay runs
 * @param expiryInterval     delay between expiry job runs (RF6)
 */
@Validated
@ConfigurationProperties("ticketing.worker")
public record WorkerProperties(
        @NotBlank String ordersQueue,
        @NotNull Duration outboxPollInterval,
        @Min(1) @Max(100) int outboxBatchSize,
        @NotNull Duration expiryInterval,
        @Min(1) @Max(1000) int expiryBatchSize) {
}
