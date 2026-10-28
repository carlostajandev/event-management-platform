package com.nequi.ticketing.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ticketing")
public record TicketingProperties(@Valid @NotNull Aws aws, @Valid @NotNull Tables tables) {

    /**
     * @param dynamodbEndpoint endpoint override for local emulators; blank in AWS
     * @param apiCallTimeout   upper bound for one SDK call including retries (ADR 0004)
     * @param apiCallAttemptTimeout upper bound for a single HTTP attempt
     */
    public record Aws(
            @NotBlank String region,
            String dynamodbEndpoint,
            @NotNull Duration apiCallTimeout,
            @NotNull Duration apiCallAttemptTimeout) {
    }

    public record Tables(
            @NotBlank String events,
            @NotBlank String orders,
            @NotBlank String idempotency,
            @NotBlank String audit,
            @NotBlank String outbox) {
    }
}
