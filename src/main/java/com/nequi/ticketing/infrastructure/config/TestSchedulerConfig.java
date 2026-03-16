package com.nequi.ticketing.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Overrides the production ShedLock configuration in the "test" profile.
 *
 * Why a separate @Configuration and not a conditional on the main config:
 * - Keeps production config free of test concerns (Single Responsibility)
 * - @Profile is explicit and discoverable — no hidden @ConditionalOnProperty
 * - The @Primary on the bean guarantees Spring picks this one when both
 *   configs are loaded during integration tests
 */
@Configuration
@Profile("test")
@EnableSchedulerLock(defaultLockAtMostFor = "PT55S")
public class TestSchedulerConfig {

    @Bean
    LockProvider lockProvider() {
        return new NoOpLockProvider();
    }
}