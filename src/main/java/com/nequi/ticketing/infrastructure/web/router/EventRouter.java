package com.nequi.ticketing.infrastructure.web.router;

import com.nequi.ticketing.infrastructure.web.handler.AvailabilityHandler;
import com.nequi.ticketing.infrastructure.web.handler.EventHandler;
import com.nequi.ticketing.infrastructure.web.versioning.ApiVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.*;

/**
 * WebFlux functional router for Event and Availability endpoints — API v1.
 *
 * <p>Versioning: URL prefix strategy (/api/v1/).
 * When v2 is needed, create {@code EventRouterV2} with /api/v2/ prefix.
 * This router stays unchanged — existing clients are not broken.
 *
 * <p>Deprecation path: annotate with {@code @ApiVersion(value=1, deprecated=true)}
 * and add a minimum 6-month sunset window before removal.
 */
@ApiVersion(1)
@Configuration
public class EventRouter {

    static final String V1 = "/api/v1/events";

    @Bean
    public RouterFunction<ServerResponse> eventRoutes(
            EventHandler eventHandler,
            AvailabilityHandler availabilityHandler) {
        return RouterFunctions.route()
                .nest(path(V1), builder -> builder
                        .POST("", accept(APPLICATION_JSON), eventHandler::createEvent)
                        .GET("", eventHandler::getAllEvents)
                        .GET("/{eventId}", eventHandler::getEvent)
                        .GET("/{eventId}/availability", availabilityHandler::getAvailability)
                )
                .build();
    }
}