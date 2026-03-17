package com.nequi.shared.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * General-purpose application beans shared across all microservices.
 *
 * <p>Providing {@link Clock} as a Spring bean allows use cases to call
 * {@code Instant.now(clock)} instead of {@code Instant.now()}, making
 * time-sensitive logic fully testable by injecting a fixed clock in tests:
 * <pre>
 *   Clock fixedClock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC);
 * </pre>
 */
@Configuration
public class ApplicationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
