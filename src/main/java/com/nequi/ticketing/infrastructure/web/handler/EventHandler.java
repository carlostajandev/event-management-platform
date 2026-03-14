package com.nequi.ticketing.infrastructure.web.handler;

import com.nequi.ticketing.application.dto.CreateEventRequest;
import com.nequi.ticketing.application.port.in.CreateEventUseCase;
import com.nequi.ticketing.application.port.in.GetEventUseCase;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * WebFlux functional handler for Event endpoints.
 *
 * <p>Functional routing style (RouterFunction + HandlerFunction) is
 * preferred over @RestController in reactive systems because:
 * - Routes are explicit and centralized in the Router class
 * - Handlers are pure functions, easier to test without MockMvc
 * - No reflection-based routing — better performance
 *
 * <p>This handler never contains business logic — it only:
 * 1. Extracts input from the request
 * 2. Delegates to the use case
 * 3. Builds the HTTP response
 */
@Component
public class EventHandler {

    private final CreateEventUseCase createEventUseCase;
    private final GetEventUseCase getEventUseCase;
    private final Validator validator;

    public EventHandler(
            CreateEventUseCase createEventUseCase,
            GetEventUseCase getEventUseCase,
            Validator validator) {
        this.createEventUseCase = createEventUseCase;
        this.getEventUseCase = getEventUseCase;
        this.validator = validator;
    }

    /**
     * POST /api/v1/events
     * Creates a new event and returns 201 Created.
     */
    public Mono<ServerResponse> createEvent(ServerRequest request) {
        return request.bodyToMono(CreateEventRequest.class)
                .flatMap(this::validate)
                .flatMap(createEventUseCase::execute)
                .flatMap(response -> ServerResponse
                        .status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response));
    }

    /**
     * GET /api/v1/events/{eventId}
     * Returns a single event or 404 if not found.
     */
    public Mono<ServerResponse> getEvent(ServerRequest request) {
        String eventId = request.pathVariable("eventId");

        return getEventUseCase.findById(eventId)
                .flatMap(response -> ServerResponse
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response));
    }

    /**
     * GET /api/v1/events
     * Returns all events as a JSON array.
     */
    public Mono<ServerResponse> getAllEvents(ServerRequest request) {
        return ServerResponse
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(getEventUseCase.findAll(), 
                      com.nequi.ticketing.application.dto.EventResponse.class);
    }

    private <T> Mono<T> validate(T body) {
        var violations = validator.validate(body);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Validation failed");
            return Mono.error(new jakarta.validation.ValidationException(message));
        }
        return Mono.just(body);
    }
}
