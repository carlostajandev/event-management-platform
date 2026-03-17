package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.mapper.EventMapper;
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
import reactor.core.publisher.Flux;
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
 * Unit tests for {@link ListEventsService}.
 *
 * <p>Verifies that the service correctly delegates to the repository,
 * maps results, and propagates empty and error signals.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ListEventsService — unit tests")
class ListEventsServiceTest {

    @Mock
    EventRepository eventRepository;

    @Spy
    EventMapper eventMapper = new EventMapper();

    @InjectMocks
    ListEventsService listEventsService;

    private Event activeEvent1;
    private Event activeEvent2;

    @BeforeEach
    void setUp() {
        Venue venue = new Venue("Atanasio Girardot", "Cra 74", "Medellín", "Colombia", 45_000);

        activeEvent1 = new Event(
                UUID.randomUUID().toString(),
                "Maluma World Tour",
                "Gira mundial de Maluma en Medellín",
                venue,
                Instant.now().plus(45, ChronoUnit.DAYS),
                new BigDecimal("280000.00"),
                "COP",
                8_000,
                6_500,
                2L,
                EventStatus.ACTIVE,
                Instant.now().minus(5, ChronoUnit.DAYS),
                Instant.now()
        );

        activeEvent2 = new Event(
                UUID.randomUUID().toString(),
                "J Balvin Presents",
                "Show especial de J Balvin",
                venue,
                Instant.now().plus(75, ChronoUnit.DAYS),
                new BigDecimal("320000.00"),
                "COP",
                12_000,
                12_000,
                0L,
                EventStatus.ACTIVE,
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now()
        );
    }

    @Test
    @DisplayName("should return all active events as Flux of EventResponse")
    void shouldReturnAllActiveEvents() {
        // Given
        when(eventRepository.findByStatus(EventStatus.ACTIVE))
                .thenReturn(Flux.just(activeEvent1, activeEvent2));

        // When
        Flux<EventResponse> result = listEventsService.execute(EventStatus.ACTIVE);

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.name()).isEqualTo("Maluma World Tour");
                    assertThat(response.status()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(response.availableCount()).isEqualTo(6_500);
                })
                .assertNext(response -> {
                    assertThat(response.name()).isEqualTo("J Balvin Presents");
                    assertThat(response.status()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(response.availableCount()).isEqualTo(12_000);
                })
                .verifyComplete();

        verify(eventRepository, times(1)).findByStatus(EventStatus.ACTIVE);
    }

    @Test
    @DisplayName("should return empty Flux when no events exist for given status")
    void shouldReturnEmptyFluxWhenNoEventsFound() {
        // Given
        when(eventRepository.findByStatus(EventStatus.CANCELLED))
                .thenReturn(Flux.empty());

        // When / Then
        StepVerifier.create(listEventsService.execute(EventStatus.CANCELLED))
                .verifyComplete();

        verify(eventRepository, times(1)).findByStatus(EventStatus.CANCELLED);
    }

    @Test
    @DisplayName("should propagate repository errors downstream")
    void shouldPropagateRepositoryErrors() {
        // Given
        RuntimeException dbError = new RuntimeException("DynamoDB GSI query failed");
        when(eventRepository.findByStatus(EventStatus.ACTIVE))
                .thenReturn(Flux.error(dbError));

        // When / Then
        StepVerifier.create(listEventsService.execute(EventStatus.ACTIVE))
                .expectErrorMatches(ex ->
                    ex instanceof RuntimeException
                    && ex.getMessage().equals("DynamoDB GSI query failed"))
                .verify();
    }

    @Test
    @DisplayName("should correctly map Event domain fields to EventResponse for each item")
    void shouldCorrectlyMapEventToEventResponse() {
        // Given
        when(eventRepository.findByStatus(EventStatus.ACTIVE))
                .thenReturn(Flux.just(activeEvent1));

        // When / Then
        StepVerifier.create(listEventsService.execute(EventStatus.ACTIVE))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(activeEvent1.id());
                    assertThat(response.name()).isEqualTo(activeEvent1.name());
                    assertThat(response.description()).isEqualTo(activeEvent1.description());
                    assertThat(response.status()).isEqualTo(activeEvent1.status());
                    assertThat(response.totalCapacity()).isEqualTo(activeEvent1.totalCapacity());
                    assertThat(response.availableCount()).isEqualTo(activeEvent1.availableCount());
                    assertThat(response.ticketPrice()).isEqualByComparingTo(activeEvent1.ticketPrice());
                    assertThat(response.currency()).isEqualTo(activeEvent1.currency());
                    assertThat(response.venue().name()).isEqualTo(activeEvent1.venue().name());
                    assertThat(response.venue().city()).isEqualTo(activeEvent1.venue().city());
                    assertThat(response.venue().country()).isEqualTo(activeEvent1.venue().country());
                })
                .verifyComplete();
    }
}
