package com.nequi.ticketing.infrastructure.web.router;

import com.nequi.ticketing.infrastructure.web.handler.EventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.*;

/**
 * WebFlux functional router for Event endpoints.
 *
 * <p>All routes are prefixed with /api/v1/events.
 * The version prefix makes it easy to introduce /api/v2 without
 * breaking existing clients.
 */
@Configuration
public class EventRouter {

    private static final String BASE_PATH = "/api/v1/events";

    @Bean
    public RouterFunction<ServerResponse> eventRoutes(EventHandler handler) {
        return RouterFunctions.route()
                .nest(path(BASE_PATH), builder -> builder
                        .POST("", accept(APPLICATION_JSON), handler::createEvent)
                        .GET("", handler::getAllEvents)
                        .GET("/{eventId}", handler::getEvent)
                )
                .build();
    }
}
