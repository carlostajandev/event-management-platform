package com.nequi.orderservice.infrastructure.web.router;

import com.nequi.orderservice.infrastructure.web.handler.OrderHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux functional router for the Order resource.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/orders} — create order (requires {@code X-Idempotency-Key} header)</li>
 *   <li>{@code GET  /api/v1/orders/{orderId}} — get order status</li>
 * </ul>
 */
@Configuration
public class OrderRouter {

    @Bean
    public RouterFunction<ServerResponse> orderRoutes(OrderHandler handler) {
        return RouterFunctions.route()
                .POST("/api/v1/orders", handler::createOrder)
                .GET("/api/v1/orders/{orderId}", handler::getOrderStatus)
                .build();
    }
}
