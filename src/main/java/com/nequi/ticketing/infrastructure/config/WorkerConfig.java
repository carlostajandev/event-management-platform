package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling runs only in the worker role: the outbox relay and the expiry job. */
@Profile("worker")
@EnableScheduling
@Configuration(proxyBeanMethods = false)
class WorkerConfig {
}
