package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.command.CreateEventCommand;
import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.mapper.EventMapper;
import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.model.Venue;
import com.nequi.shared.domain.port.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreateEventService}.
 *
 * <p>Verifies the mapping logic, metric increment, and reactive pipeline
 * without touching DynamoDB. All dependencies are mocked with Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateEventService — unit tests")
class CreateEventServiceTest {

    @Mock
    EventRepository eventRepository;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter counter;

    @Spy
    EventMapper eventMapper = new EventMapper();

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    CreateEventService createEventService;

    private CreateEventCommand validRequest;
    private Event              savedEvent;

    @BeforeEach
    void setUp() {
        Venue venue = new Venue("Movistar Arena", "Calle 26 # 22-20", "Bogotá", "Colombia", 20_000);

        validRequest = new CreateEventCommand(
                "Feid Live 2026",
                "Concierto de reggaetón en Bogotá",
                venue,
                Instant.now().plus(30, ChronoUnit.DAYS),
                new BigDecimal("150000.00"),
                "COP",
                10_000
        );

        savedEvent = new Event(
                UUID.randomUUID().toString(),
                validRequest.name(),
                validRequest.description(),
                venue,
                validRequest.eventDate(),
                validRequest.ticketPrice(),
                validRequest.currency(),
                validRequest.totalCapacity(),
                validRequest.totalCapacity(),   // availableCount = totalCapacity on creation
                0L,
                EventStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("should create event successfully and return EventResponse with correct fields")
    void shouldCreateEventSuccessfully() {
        // Given
        when(eventRepository.save(any(Event.class))).thenReturn(Mono.just(savedEvent));
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        // When
        Mono<EventResponse> result = createEventService.execute(validRequest);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.id()).isNotNull().isNotBlank();
                    assertThat(response.name()).isEqualTo("Feid Live 2026");
                    assertThat(response.description()).isEqualTo("Concierto de reggaetón en Bogotá");
                    assertThat(response.status()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(response.totalCapacity()).isEqualTo(10_000);
                    assertThat(response.availableCount()).isEqualTo(10_000);
                    assertThat(response.ticketPrice()).isEqualByComparingTo(new BigDecimal("150000.00"));
                    assertThat(response.currency()).isEqualTo("COP");
                    assertThat(response.venue()).isNotNull();
                    assertThat(response.venue().name()).isEqualTo("Movistar Arena");
                    assertThat(response.venue().city()).isEqualTo("Bogotá");
                    assertThat(response.createdAt()).isNotNull();
                    assertThat(response.updatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(eventRepository, times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("should increment events.created.total counter on successful event creation")
    void shouldIncrementMetricOnEventCreation() {
        // Given
        when(eventRepository.save(any(Event.class))).thenReturn(Mono.just(savedEvent));
        when(meterRegistry.counter("events.created.total")).thenReturn(counter);

        // When
        StepVerifier.create(createEventService.execute(validRequest))
                .expectNextCount(1)
                .verifyComplete();

        // Then — counter was fetched and incremented
        verify(meterRegistry, times(1)).counter("events.created.total");
        verify(counter, times(1)).increment();
    }

    @Test
    @DisplayName("should propagate repository error as Mono.error")
    void shouldPropagateRepositoryError() {
        // Given — counter stub is NOT set up: if save fails the counter is never reached,
        // so we must NOT stub meterRegistry here (MockitoExtension strict-mode would flag it)
        RuntimeException dbError = new RuntimeException("DynamoDB unavailable");
        when(eventRepository.save(any(Event.class))).thenReturn(Mono.error(dbError));

        // When / Then
        StepVerifier.create(createEventService.execute(validRequest))
                .expectErrorMatches(ex ->
                    ex instanceof RuntimeException && ex.getMessage().contains("DynamoDB unavailable"))
                .verify();

        // Counter must never be incremented on failure
        verify(meterRegistry, times(0)).counter(anyString());
        verify(counter, times(0)).increment();
    }

    @Test
    @DisplayName("should set availableCount equal to totalCapacity on new event")
    void shouldSetAvailableCountEqualToTotalCapacity() {
        // Given — capture the Event passed to save
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event capturedEvent = invocation.getArgument(0);
            assertThat(capturedEvent.availableCount()).isEqualTo(capturedEvent.totalCapacity());
            assertThat(capturedEvent.status()).isEqualTo(EventStatus.ACTIVE);
            assertThat(capturedEvent.version()).isEqualTo(0L);
            return Mono.just(capturedEvent);
        });
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        // When / Then
        StepVerifier.create(createEventService.execute(validRequest))
                .assertNext(response -> {
                    assertThat(response.availableCount()).isEqualTo(validRequest.totalCapacity());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("toResponse should correctly map all Event domain fields to EventResponse")
    void toResponseShouldMapAllFields() {
        // Given — use the EventMapper directly (mapping was extracted from the service)
        EventResponse response = eventMapper.toResponse(savedEvent);

        // Then
        assertThat(response.id()).isEqualTo(savedEvent.id());
        assertThat(response.name()).isEqualTo(savedEvent.name());
        assertThat(response.description()).isEqualTo(savedEvent.description());
        assertThat(response.status()).isEqualTo(savedEvent.status());
        assertThat(response.totalCapacity()).isEqualTo(savedEvent.totalCapacity());
        assertThat(response.availableCount()).isEqualTo(savedEvent.availableCount());
        assertThat(response.ticketPrice()).isEqualByComparingTo(savedEvent.ticketPrice());
        assertThat(response.currency()).isEqualTo(savedEvent.currency());
        assertThat(response.createdAt()).isEqualTo(savedEvent.createdAt());
        assertThat(response.updatedAt()).isEqualTo(savedEvent.updatedAt());
        // Venue mapping
        assertThat(response.venue().name()).isEqualTo(savedEvent.venue().name());
        assertThat(response.venue().address()).isEqualTo(savedEvent.venue().address());
        assertThat(response.venue().city()).isEqualTo(savedEvent.venue().city());
        assertThat(response.venue().country()).isEqualTo(savedEvent.venue().country());
        assertThat(response.venue().capacity()).isEqualTo(savedEvent.venue().capacity());
    }
}
