package com.nequi.consumerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the consumer-service microservice.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>SQS consumer for ORDER_PLACED events ({@code emp-purchase-orders} queue)</li>
 *   <li>Order confirmation: PENDING_CONFIRMATION → CONFIRMED</li>
 *   <li>Reservation confirmation: ACTIVE → CONFIRMED</li>
 *   <li>Outbox Poller: polls {@code emp-outbox} every 5 seconds and publishes to SQS</li>
 * </ul>
 *
 * <p>Listens on port 8084 (management/health only — no HTTP API).
 * Java 25 Virtual Threads enabled for blocking I/O layers.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"com.nequi.consumerservice", "com.nequi.shared"})
public class ConsumerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerServiceApplication.class, args);
    }
}
