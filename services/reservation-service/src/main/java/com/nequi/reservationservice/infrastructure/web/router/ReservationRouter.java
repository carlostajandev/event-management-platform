package com.nequi.reservationservice.infrastructure.web.router;

import com.nequi.reservationservice.infrastructure.web.handler.ReservationHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * WebFlux functional router for the Reservation resource.
 *
 * <p>Functional routing is preferred over annotation-based controllers in WebFlux
 * because it keeps the routing table explicit and testable without a full
 * Spring context. Routes are composed from a single {@link RouterFunction} bean.
 */
@Configuration
public class ReservationRouter {

    /**
     * Declares all routes for the reservation resource:
     * <ul>
     *   <li>POST   /api/v1/reservations         — reserve tickets</li>
     *   <li>GET    /api/v1/reservations/{id}     — get reservation by id</li>
     *   <li>DELETE /api/v1/reservations/{id}     — cancel reservation (X-User-Id header required)</li>
     * </ul>
     */
    @Bean
    public RouterFunction<ServerResponse> reservationRoutes(ReservationHandler handler) {
        return route()
                .POST("/api/v1/reservations", handler::reserve)
                .GET("/api/v1/reservations/{id}", handler::get)
                .DELETE("/api/v1/reservations/{id}", handler::cancel)
                .build();
    }
}
