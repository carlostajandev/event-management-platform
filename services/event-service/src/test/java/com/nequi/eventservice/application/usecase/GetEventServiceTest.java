package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.mapper.EventMapper;
import com.nequi.shared.domain.exception.EventNotFoundException;
import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.model.Venue;
import com.nequi.shared.domain.port.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
 * Unit tests for {@link GetEventService}.
 *
 * <p>Covers the happy path (event found) and the not-found scenario
 * (empty Mono from repository → EventNotFoundException).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetEventService — unit tests")
class GetEventServiceTest {

    @Mock
    EventRepository eventRepository;

    @Spy
    EventMapper eventMapper = new EventMapper();

    @InjectMocks
    GetEventService getEventService;

    private static final String EVENT_ID = UUID.randomUUID().toString();
    private Event                existingEvent;

    @BeforeEach
    void setUp() {
        Venue venue = new Venue("El Campin", "Cra 30 # 57-60", "Bogotá", "Colombia", 50_000);

        existingEvent = new Event(
                EVENT_ID,
                "Rock al Parque 2026",
                "Festival de rock más grande de Latinoamérica",
                venue,
                Instant.now().plus(60, ChronoUnit.DAYS),
                new BigDecimal("0.00"),     // free event
                "COP",
                50_000,
                48_000,
                3L,
                EventStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now()
        );
    }

    @Test
    @DisplayName("should return EventResponse when event is found")
    void shouldReturnEventWhenFound() {
        // Given
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(existingEvent));

        // When
        Mono<EventResponse> result = getEventService.execute(EVENT_ID);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(EVENT_ID);
                    assertThat(response.name()).isEqualTo("Rock al Parque 2026");
                    assertThat(response.status()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(response.totalCapacity()).isEqualTo(50_000);
                    assertThat(response.availableCount()).isEqualTo(48_000);
                    assertThat(response.ticketPrice()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(response.venue()).isNotNull();
                    assertThat(response.venue().name()).isEqualTo("El Campin");
                    assertThat(response.venue().city()).isEqualTo("Bogotá");
                })
                .verifyComplete();

        verify(eventRepository, times(1)).findById(EVENT_ID);
    }

    @Test
    @DisplayName("should throw EventNotFoundException when event is not found")
    void shouldThrowEventNotFoundExceptionWhenNotFound() {
        // Given
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(getEventService.execute(EVENT_ID))
                .expectErrorMatches(ex ->
                    ex instanceof EventNotFoundException
                    && ex.getMessage().contains(EVENT_ID))
                .verify();

        verify(eventRepository, times(1)).findById(EVENT_ID);
    }

    @Test
    @DisplayName("should propagate repository errors as-is")
    void shouldPropagateRepositoryErrors() {
        // Given
        RuntimeException dbError = new RuntimeException("Connection timeout");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.error(dbError));

        // When / Then
        StepVerifier.create(getEventService.execute(EVENT_ID))
                .expectErrorMatches(ex ->
                    ex instanceof RuntimeException
                    && ex.getMessage().equals("Connection timeout"))
                .verify();
    }

    @Test
    @DisplayName("toResponse should map all Event fields to EventResponse correctly")
    void toResponseShouldMapAllFields() {
        // When — mapping was extracted to EventMapper
        EventResponse response = eventMapper.toResponse(existingEvent);

        // Then
        assertThat(response.id()).isEqualTo(existingEvent.id());
        assertThat(response.name()).isEqualTo(existingEvent.name());
        assertThat(response.description()).isEqualTo(existingEvent.description());
        assertThat(response.status()).isEqualTo(existingEvent.status());
        assertThat(response.totalCapacity()).isEqualTo(existingEvent.totalCapacity());
        assertThat(response.availableCount()).isEqualTo(existingEvent.availableCount());
        assertThat(response.currency()).isEqualTo(existingEvent.currency());
        assertThat(response.createdAt()).isEqualTo(existingEvent.createdAt());
        assertThat(response.updatedAt()).isEqualTo(existingEvent.updatedAt());
        // Venue mapping
        assertThat(response.venue().name()).isEqualTo(existingEvent.venue().name());
        assertThat(response.venue().address()).isEqualTo(existingEvent.venue().address());
        assertThat(response.venue().capacity()).isEqualTo(existingEvent.venue().capacity());
    }
}
