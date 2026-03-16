package com.nequi.ticketing.infrastructure.web.router;

import com.nequi.ticketing.infrastructure.web.handler.OrderHandler;
import com.nequi.ticketing.infrastructure.web.versioning.ApiVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

/**
 * WebFlux functional router for Order endpoints — API v1.
 *
 * @see EventRouter for versioning strategy documentation.
 */
@ApiVersion(1)
@Configuration
public class OrderRouter {

    static final String V1 = "/api/v1/orders";

    @Bean
    public RouterFunction<ServerResponse> orderRoutes(OrderHandler handler) {
        return RouterFunctions.route()
                .nest(path(V1), builder -> builder
                        .POST("", accept(APPLICATION_JSON), handler::createOrder)
                        .GET("/{orderId}", handler::getOrderStatus)
                )
                .build();
    }
}