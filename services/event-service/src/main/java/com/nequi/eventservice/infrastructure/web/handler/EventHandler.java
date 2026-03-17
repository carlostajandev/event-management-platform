package com.nequi.eventservice.infrastructure.web.handler;

import com.nequi.eventservice.application.command.CreateEventCommand;
import com.nequi.eventservice.application.dto.AvailabilityResponse;
import com.nequi.eventservice.application.dto.CreateEventRequest;
import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.port.in.CreateEventUseCase;
import com.nequi.eventservice.application.port.in.GetAvailabilityUseCase;
import com.nequi.eventservice.application.port.in.GetEventUseCase;
import com.nequi.eventservice.application.port.in.ListEventsUseCase;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.model.Venue;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WebFlux functional handler for the Event resource.
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Parsing and validating request bodies / path variables / query parameters</li>
 *   <li>Delegating to the appropriate input port (use-case)</li>
 *   <li>Building {@link ServerResponse} with correct status codes and content types</li>
 * </ul>
 *
 * <p>Validation errors return HTTP 400 with a structured message listing all
 * constraint violations. Domain exceptions propagate to the global error handler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventHandler {

    private static final String EVENT_ID_PATH_VARIABLE = "eventId";
    private static final String STATUS_QUERY_PARAM     = "status";

    private final CreateEventUseCase     createEventUseCase;
    private final GetEventUseCase        getEventUseCase;
    private final ListEventsUseCase      listEventsUseCase;
    private final GetAvailabilityUseCase getAvailabilityUseCase;
    private final Validator              validator;

    // ── POST /api/v1/events ───────────────────────────────────────────────────

    /**
     * Creates a new event. Returns 201 Created with Location header and the event body.
     */
    public Mono<ServerResponse> createEvent(ServerRequest request) {
        return request.bodyToMono(CreateEventRequest.class)
                .flatMap(this::validateRequest)
                .map(dto -> new CreateEventCommand(
                        dto.name(), dto.description(),
                        new Venue(dto.venue().name(), dto.venue().address(),
                                  dto.venue().city(), dto.venue().country(), dto.venue().capacity()),
                        dto.eventDate(), dto.ticketPrice(), dto.currency(), dto.totalCapacity()))
                .flatMap(createEventUseCase::execute)
                .flatMap(response ->
                    ServerResponse
                        .created(URI.create("/api/v1/events/" + response.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response)
                )
                .doOnError(ex -> log.error("Error in createEvent handler: {}", ex.getMessage()));
    }

    // ── GET /api/v1/events/{eventId} ──────────────────────────────────────────

    /**
     * Retrieves a single event by id. Returns 200 OK or propagates 404 via error handler.
     */
    public Mono<ServerResponse> getEvent(ServerRequest request) {
        String eventId = request.pathVariable(EVENT_ID_PATH_VARIABLE);
        return getEventUseCase.execute(eventId)
                .flatMap(response ->
                    ServerResponse
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response)
                );
    }

    // ── GET /api/v1/events?status=ACTIVE ─────────────────────────────────────

    /**
     * Lists events filtered by status query parameter. Defaults to {@code ACTIVE}.
     * Returns 200 OK with a streaming JSON array.
     */
    public Mono<ServerResponse> listEvents(ServerRequest request) {
        EventStatus status = request.queryParam(STATUS_QUERY_PARAM)
                .map(s -> {
                    try {
                        return EventStatus.valueOf(s.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(
                                "Invalid status value: '%s'. Allowed: ACTIVE, CANCELLED, SOLD_OUT, COMPLETED".formatted(s));
                    }
                })
                .orElse(EventStatus.ACTIVE);

        return ServerResponse
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(listEventsUseCase.execute(status), EventResponse.class);
    }

    // ── GET /api/v1/events/{eventId}/availability ─────────────────────────────

    /**
     * Returns ticket availability for the given event. Returns 200 OK.
     */
    public Mono<ServerResponse> getAvailability(ServerRequest request) {
        String eventId = request.pathVariable(EVENT_ID_PATH_VARIABLE);
        return getAvailabilityUseCase.execute(eventId)
                .flatMap(response ->
                    ServerResponse
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(response)
                );
    }

    // ── Validation helper ─────────────────────────────────────────────────────

    /**
     * Validates a {@link CreateEventRequest} using Bean Validation.
     * Returns a {@link Mono#error} with {@link IllegalArgumentException} containing
     * all violations if validation fails, so the global error handler maps it to 400.
     */
    private Mono<CreateEventRequest> validateRequest(CreateEventRequest request) {
        Set<ConstraintViolation<CreateEventRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return Mono.just(request);
        }
        String message = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        log.warn("Validation failed for CreateEventRequest: {}", message);
        return Mono.error(new IllegalArgumentException("Validation failed: " + message));
    }
}