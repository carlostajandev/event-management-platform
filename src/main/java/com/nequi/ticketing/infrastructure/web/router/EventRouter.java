package com.nequi.ticketing.infrastructure.web.router;

import com.nequi.ticketing.infrastructure.web.handler.AvailabilityHandler;
import com.nequi.ticketing.infrastructure.web.handler.EventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.*;

/**
 * WebFlux functional router for Event and Availability endpoints.
 *
 * <p>All routes are prefixed with /api/v1/events.
 */
@Configuration
public class EventRouter {

    private static final String BASE_PATH = "/api/v1/events";

    @Bean
    public RouterFunction<ServerResponse> eventRoutes(
            EventHandler eventHandler,
            AvailabilityHandler availabilityHandler) {
        return RouterFunctions.route()
                .nest(path(BASE_PATH), builder -> builder
                        .POST("", accept(APPLICATION_JSON), eventHandler::createEvent)
                        .GET("", eventHandler::getAllEvents)
                        .GET("/{eventId}", eventHandler::getEvent)
                        .GET("/{eventId}/availability", availabilityHandler::getAvailability)
                )
                .build();
    }
}