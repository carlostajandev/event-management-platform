package com.nequi.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point — Event Management Platform.
 *
 * <p><b>Architecture (Clean Architecture):</b>
 * <pre>
 *   domain/          — Entities, Value Objects, Domain Services. Zero dependencies.
 *   application/     — Use Cases + Port interfaces. Depends only on domain.
 *   infrastructure/  — DynamoDB, SQS, HTTP adapters. Depends on application.
 *   shared/          — Cross-cutting: error handling, logging, validation.
 * </pre>
 *
 * <p><b>Concurrency model:</b>
 * <ul>
 *   <li>Spring WebFlux + Project Reactor (non-blocking Netty event loop).</li>
 *   <li>Virtual Threads via {@code spring.threads.virtual.enabled=true}.</li>
 *   <li>DynamoDB conditional writes for optimistic locking (prevents overselling).</li>
 *   <li>SQS for async order processing with at-least-once delivery.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EventManagementPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventManagementPlatformApplication.class, args);
	}
}