package com.nequi.reservationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the reservation-service microservice.
 *
 * <p>Component scan includes both this service's package and the shared infrastructure
 * package so that shared beans (DynamoDB config, SQS config, Jackson config,
 * GlobalErrorHandler, CorrelationIdFilter) are auto-detected.
 *
 * <p>{@link EnableScheduling} activates the reconciliation scheduler
 * ({@link com.nequi.reservationservice.infrastructure.scheduler.ReservationExpiryScheduler})
 * which is conditionally enabled via {@code scheduler.reservation-expiry.enabled}.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.nequi.reservationservice", "com.nequi.shared"})
public class ReservationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationServiceApplication.class, args);
    }
}
