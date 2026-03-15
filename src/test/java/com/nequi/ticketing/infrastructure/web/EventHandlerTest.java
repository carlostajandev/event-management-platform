package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.dto.AvailabilityResponse;
import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.application.port.in.CreateEventUseCase;
import com.nequi.ticketing.application.port.in.GetAvailabilityUseCase;
import com.nequi.ticketing.application.port.in.GetEventUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.infrastructure.web.handler.AvailabilityHandler;
import com.nequi.ticketing.infrastructure.web.handler.EventHandler;
import com.nequi.ticketing.infrastructure.web.router.EventRouter;
import com.nequi.ticketing.shared.error.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest
@Import({EventRouter.class, EventHandler.class, AvailabilityHandler.class, GlobalErrorHandler.class})
@DisplayName("EventHandler HTTP")
class EventHandlerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean private CreateEventUseCase createEventUseCase;
    @MockitoBean private GetEventUseCase getEventUseCase;
    @MockitoBean private GetAvailabilityUseCase getAvailabilityUseCase;

    @Test
    @DisplayName("POST /api/v1/events should return 201")
    void shouldCreateEvent() {
        when(createEventUseCase.execute(any())).thenReturn(Mono.just(validEventResponse()));

        webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Bad Bunny","eventDate":"2027-12-15T20:00:00Z",
                         "venueName":"El Campin","venueCity":"Bogota","venueCountry":"Colombia",
                         "totalCapacity":50000,"ticketPrice":350000,"currency":"COP"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.eventId").isEqualTo("evt_test123");
    }

    @Test
    @DisplayName("GET /api/v1/events/{eventId} should return 200")
    void shouldGetEventById() {
        when(getEventUseCase.findById("evt_test123")).thenReturn(Mono.just(validEventResponse()));

        webTestClient.get()
                .uri("/api/v1/events/evt_test123")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.eventId").isEqualTo("evt_test123");
    }

    @Test
    @DisplayName("GET /api/v1/events/{eventId} should return 404 when not found")
    void shouldReturn404WhenEventNotFound() {
        when(getEventUseCase.findById("evt_unknown"))
                .thenReturn(Mono.error(new EventNotFoundException(EventId.of("evt_unknown"))));

        webTestClient.get()
                .uri("/api/v1/events/evt_unknown")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404);
    }

    @Test
    @DisplayName("GET /api/v1/events should return 200")
    void shouldGetAllEvents() {
        when(getEventUseCase.findAll()).thenReturn(Flux.just(validEventResponse()));

        webTestClient.get().uri("/api/v1/events").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("GET /api/v1/events/{eventId}/availability should return 200")
    void shouldGetAvailability() {
        when(getAvailabilityUseCase.getAvailability("evt_test123"))
                .thenReturn(Mono.just(
                        new AvailabilityResponse("evt_test123", 100L, 5L, 10L, 200, true)));

        webTestClient.get()
                .uri("/api/v1/events/evt_test123/availability")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.availableTickets").isEqualTo(100);
    }

    private EventResponse validEventResponse() {
        return new EventResponse(
                "evt_test123", "Bad Bunny World Tour", "desc",
                Instant.now().plus(30, ChronoUnit.DAYS),
                "El Campin", "Bogota", "Colombia",
                50000, 50000, new BigDecimal("350000"), "COP", "DRAFT",
                Instant.now(), Instant.now());
    }
}