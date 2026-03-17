package com.nequi.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point for the order-service microservice.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Idempotent order creation from active reservations</li>
 *   <li>Atomic order + outbox write via DynamoDB TransactWriteItems</li>
 *   <li>Order status retrieval</li>
 * </ul>
 *
 * <p>Listens on port 8083 by default (see {@code application.yml}).
 * Java 25 Virtual Threads enabled for blocking I/O layers.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.nequi.orderservice", "com.nequi.shared"})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
