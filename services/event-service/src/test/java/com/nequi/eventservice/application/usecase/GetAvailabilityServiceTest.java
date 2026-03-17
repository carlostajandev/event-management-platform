package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.dto.AvailabilityResponse;
import com.nequi.shared.domain.exception.EventNotFoundException;
import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.model.Venue;
import com.nequi.shared.domain.port.EventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GetAvailabilityService}.
 *
 * <p>Uses a real {@link SimpleMeterRegistry} to verify gauge registration,
 * and a mocked {@link EventRepository} to control fixture data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetAvailabilityService — unit tests")
class GetAvailabilityServiceTest {

    @Mock
    EventRepository eventRepository;

    // Real registry so we can assert gauge values
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    GetAvailabilityService getAvailabilityService;

    private static final String EVENT_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        getAvailabilityService = new GetAvailabilityService(eventRepository, meterRegistry);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Event buildEvent(int availableCount) {
        Venue venue = new Venue("Parque Simón Bolívar", "Cra 48", "Bogotá", "Colombia", 80_000);
        return new Event(
                EVENT_ID,
                "Festival Estéreo Picnic",
                "Festival de música electrónica y alternativa",
                venue,
                Instant.now().plus(90, ChronoUnit.DAYS),
                new BigDecimal("580000.00"),
                "COP",
                15_000,
                availableCount,
                5L,
                EventStatus.ACTIVE,
                Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now()
        );
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return AvailabilityResponse with correct availableCount and available=true")
    void shouldReturnAvailabilityWithCorrectCount() {
        // Given
        Event eventWithTickets = buildEvent(5_000);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(eventWithTickets));

        // When
        Mono<AvailabilityResponse> result = getAvailabilityService.execute(EVENT_ID);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.eventId()).isEqualTo(EVENT_ID);
                    assertThat(response.availableCount()).isEqualTo(5_000);
                    assertThat(response.available()).isTrue();
                })
                .verifyComplete();

        verify(eventRepository, times(1)).findById(EVENT_ID);
    }

    @Test
    @DisplayName("should return available=false when availableCount is zero")
    void shouldReturnNotAvailableWhenZeroTickets() {
        // Given
        Event soldOutEvent = buildEvent(0);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(soldOutEvent));

        // When / Then
        StepVerifier.create(getAvailabilityService.execute(EVENT_ID))
                .assertNext(response -> {
                    assertThat(response.eventId()).isEqualTo(EVENT_ID);
                    assertThat(response.availableCount()).isEqualTo(0);
                    assertThat(response.available()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should throw EventNotFoundException when event does not exist")
    void shouldThrowEventNotFoundExceptionWhenEventNotFound() {
        // Given
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(getAvailabilityService.execute(EVENT_ID))
                .expectErrorMatches(ex ->
                    ex instanceof EventNotFoundException
                    && ex.getMessage().contains(EVENT_ID))
                .verify();
    }

    @Test
    @DisplayName("should register a Micrometer gauge tagged with eventId")
    void shouldRegisterGaugeWithEventIdTag() {
        // Given
        Event eventWithTickets = buildEvent(3_500);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(eventWithTickets));

        // When
        StepVerifier.create(getAvailabilityService.execute(EVENT_ID))
                .expectNextCount(1)
                .verifyComplete();

        // Then — verify gauge exists with the correct tag
        Gauge gauge = meterRegistry.find("event.available_tickets")
                .tag("eventId", EVENT_ID)
                .gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(3_500.0);
    }

    @Test
    @DisplayName("should return available=true when availableCount is exactly 1")
    void shouldReturnAvailableTrueWhenOneTicketLeft() {
        // Given
        Event lastTicketEvent = buildEvent(1);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(lastTicketEvent));

        // When / Then
        StepVerifier.create(getAvailabilityService.execute(EVENT_ID))
                .assertNext(response -> {
                    assertThat(response.availableCount()).isEqualTo(1);
                    assertThat(response.available()).isTrue();
                })
                .verifyComplete();
    }
}
