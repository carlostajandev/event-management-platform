package com.nequi.eventservice.infrastructure.web.router;

import com.nequi.eventservice.infrastructure.web.handler.EventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Functional WebFlux router for the Event resource.
 *
 * <p>Prefers the functional routing style over {@code @RestController} annotations
 * because:
 * <ul>
 *   <li>Routing declarations are explicit and visible in a single place</li>
 *   <li>Handlers are plain Spring components, fully testable without a web context</li>
 *   <li>Request predicates can be composed without annotation magic</li>
 * </ul>
 *
 * <p>Routes:
 * <pre>
 *   POST   /api/v1/events                        → create
 *   GET    /api/v1/events/{eventId}              → get
 *   GET    /api/v1/events?status=ACTIVE          → list
 *   GET    /api/v1/events/{eventId}/availability → availability
 * </pre>
 */
@Configuration
public class EventRouter {

    @Bean
    public RouterFunction<ServerResponse> eventRoutes(EventHandler handler) {
        return route()
                .POST("/api/v1/events",                              handler::createEvent)
                .GET("/api/v1/events/{eventId}/availability",        handler::getAvailability)
                .GET("/api/v1/events/{eventId}",                     handler::getEvent)
                .GET("/api/v1/events",                               handler::listEvents)
                .build();
    }
}