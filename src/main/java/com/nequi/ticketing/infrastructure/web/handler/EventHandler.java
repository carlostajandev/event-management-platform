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
 * <p>Supports pagination via query params:
 * GET /api/v1/events?page=0&size=20
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
     * GET /api/v1/events?page=0&size=20
     * Supports pagination via query parameters.
     * Defaults to page=0, size=20 if not provided.
     */
    public Mono<ServerResponse> getAllEvents(ServerRequest request) {
        int page = request.queryParam("page")
                .map(Integer::parseInt)
                .orElse(0);
        int size = request.queryParam("size")
                .map(Integer::parseInt)
                .orElse(20);

        if (page < 0 || size <= 0 || size > 100) {
            return ServerResponse.badRequest()
                    .bodyValue("Invalid pagination: page >= 0, 0 < size <= 100");
        }

        return getEventUseCase.findPaged(page, size)
                .flatMap(pagedResponse -> ServerResponse
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(pagedResponse));
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