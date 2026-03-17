package com.nequi.reservationservice.infrastructure.web.handler;

import com.nequi.reservationservice.application.command.CancelReservationCommand;
import com.nequi.reservationservice.application.command.ReserveTicketsCommand;
import com.nequi.reservationservice.application.dto.ReservationResponse;
import com.nequi.reservationservice.application.dto.ReserveTicketsRequest;
import com.nequi.reservationservice.application.port.in.CancelReservationUseCase;
import com.nequi.reservationservice.application.port.in.GetReservationUseCase;
import com.nequi.reservationservice.application.port.in.ReserveTicketsUseCase;
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
 * WebFlux functional handler for the Reservation resource.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Parsing and validating request bodies / path variables / headers</li>
 *   <li>Delegating to the appropriate input port (use-case)</li>
 *   <li>Building {@link ServerResponse} with correct HTTP status codes and content types</li>
 * </ul>
 *
 * <p>Validation errors return HTTP 400 with a structured message listing all
 * constraint violations. Domain exceptions propagate to the
 * {@link com.nequi.shared.infrastructure.util.GlobalErrorHandler}.
 *
 * <p>The {@code X-User-Id} header is required for cancellation to identify the
 * requesting user without a full authentication token in this service boundary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationHandler {

    private static final String RESERVATION_ID_PATH_VAR = "id";
    private static final String USER_ID_HEADER          = "X-User-Id";

    private final ReserveTicketsUseCase   reserveTicketsUseCase;
    private final GetReservationUseCase   getReservationUseCase;
    private final CancelReservationUseCase cancelReservationUseCase;
    private final Validator               validator;

    // ── POST /api/v1/reservations ─────────────────────────────────────────────

    /**
     * Creates a new reservation. Returns 201 Created with Location header.
     */
    public Mono<ServerResponse> reserve(ServerRequest request) {
        return request.bodyToMono(ReserveTicketsRequest.class)
                .flatMap(this::validateRequest)
                .map(dto -> new ReserveTicketsCommand(dto.eventId(), dto.userId(), dto.seatsCount()))
                .flatMap(reserveTicketsUseCase::execute)
                .flatMap(response ->
                        ServerResponse
                                .created(URI.create("/api/v1/reservations/" + response.id()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response)
                )
                .doOnError(ex -> log.error("Error in reserve handler: {}", ex.getMessage()));
    }

    // ── GET /api/v1/reservations/{id} ─────────────────────────────────────────

    /**
     * Retrieves a reservation by id. Returns 200 OK or propagates 404.
     */
    public Mono<ServerResponse> get(ServerRequest request) {
        String reservationId = request.pathVariable(RESERVATION_ID_PATH_VAR);
        return getReservationUseCase.execute(reservationId)
                .flatMap(response ->
                        ServerResponse
                                .ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response)
                );
    }

    // ── DELETE /api/v1/reservations/{id} ─────────────────────────────────────

    /**
     * Cancels an active reservation. Requires {@code X-User-Id} header.
     * Returns 204 No Content on success, 400 if header is missing.
     */
    public Mono<ServerResponse> cancel(ServerRequest request) {
        String reservationId = request.pathVariable(RESERVATION_ID_PATH_VAR);
        String userId = request.headers().firstHeader(USER_ID_HEADER);

        if (userId == null || userId.isBlank()) {
            return ServerResponse
                    .status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"error\":\"Missing required header: X-User-Id\"}");
        }

        return cancelReservationUseCase.execute(new CancelReservationCommand(reservationId, userId))
                .then(ServerResponse.noContent().build())
                .doOnError(ex -> log.error("Error in cancel handler: reservationId={}, error={}", reservationId, ex.getMessage()));
    }

    // ── Validation helper ─────────────────────────────────────────────────────

    /**
     * Validates a {@link ReserveTicketsRequest} using Bean Validation.
     * Returns {@link Mono#error} with {@link IllegalArgumentException} on violations.
     */
    private Mono<ReserveTicketsRequest> validateRequest(ReserveTicketsRequest request) {
        Set<ConstraintViolation<ReserveTicketsRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return Mono.just(request);
        }
        String message = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        log.warn("Validation failed for ReserveTicketsRequest: {}", message);
        return Mono.error(new IllegalArgumentException("Validation failed: " + message));
    }
}
