package com.nequi.eventservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Event Service — microservice entry point.
 *
 * <p>Manages the Event aggregate: creation, retrieval, status listing,
 * and ticket availability queries. Ticket reservation/release are
 * orchestrated by the reservation-service, which calls this service's
 * domain ports directly via the shared {@code EventRepository}.
 *
 * <p>Component scan covers both the service package and the shared
 * infrastructure package so that beans defined in {@code shared-infrastructure}
 * (DynamoDbConfig, CorrelationIdFilter, GlobalErrorHandler, JacksonConfig)
 * are picked up automatically without requiring additional {@code @Import}.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.nequi.eventservice",
        "com.nequi.shared"
})
public class EventServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventServiceApplication.class, args);
    }
}
