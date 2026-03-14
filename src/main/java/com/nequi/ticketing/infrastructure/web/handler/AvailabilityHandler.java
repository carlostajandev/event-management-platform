package com.nequi.ticketing.infrastructure.web.handler;

import com.nequi.ticketing.application.port.in.GetAvailabilityUseCase;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * WebFlux handler for the real-time availability endpoint.
 */
@Component
public class AvailabilityHandler {

    private final GetAvailabilityUseCase getAvailabilityUseCase;

    public AvailabilityHandler(GetAvailabilityUseCase getAvailabilityUseCase) {
        this.getAvailabilityUseCase = getAvailabilityUseCase;
    }

    /**
     * GET /api/v1/events/{eventId}/availability
     * Returns current ticket availability snapshot.
     */
    public Mono<ServerResponse> getAvailability(ServerRequest request) {
        String eventId = request.pathVariable("eventId");

        return getAvailabilityUseCase.getAvailability(eventId)
                .flatMap(response -> ServerResponse
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response));
    }
}
