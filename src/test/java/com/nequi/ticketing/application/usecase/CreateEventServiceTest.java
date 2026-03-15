package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.CreateEventRequest;
import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.Venue;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateEventService")
class CreateEventServiceTest {

    @Mock
    private EventRepository eventRepository;

    private CreateEventService service;

    @BeforeEach
    void setUp() {
        service = new CreateEventService(eventRepository);
    }

    @Test
    @DisplayName("should create event successfully and return response")
    void shouldCreateEventSuccessfully() {
        CreateEventRequest request = validRequest();
        Event savedEvent = buildEvent(request);

        when(eventRepository.save(any(Event.class)))
                .thenReturn(Mono.just(savedEvent));

        StepVerifier.create(service.execute(request))
                .assertNext(response -> {
                    assertThat(response.name()).isEqualTo("Bad Bunny World Tour");
                    assertThat(response.totalCapacity()).isEqualTo(50000);
                    assertThat(response.availableTickets()).isEqualTo(50000);
                    assertThat(response.status()).isEqualTo("DRAFT");
                    assertThat(response.venueName()).isEqualTo("Estadio El Campín");
                    assertThat(response.currency()).isEqualTo("COP");
                    assertThat(response.eventId()).isNotNull();
                })
                .verifyComplete();

        verify(eventRepository).save(any(Event.class));
    }

    @Test
    @DisplayName("should propagate repository error as reactive error")
    void shouldPropagateRepositoryError() {
        CreateEventRequest request = validRequest();

        when(eventRepository.save(any(Event.class)))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB unavailable")));

        StepVerifier.create(service.execute(request))
                .expectErrorMatches(ex ->
                        ex instanceof RuntimeException &&
                        ex.getMessage().contains("DynamoDB unavailable"))
                .verify();
    }

    @Test
    @DisplayName("should fail when event date is in the past")
    void shouldFailWhenEventDateIsInPast() {
        CreateEventRequest request = new CreateEventRequest(
                "Past Event",
                "description",
                Instant.now().minus(1, ChronoUnit.DAYS),  // past date
                "Venue", "City", "Country",
                1000,
                new BigDecimal("100.00"),
                "COP"
        );

        StepVerifier.create(service.execute(request))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException &&
                        ex.getMessage().contains("future"))
                .verify();
    }

    private CreateEventRequest validRequest() {
        return new CreateEventRequest(
                "Bad Bunny World Tour",
                "El concierto del año",
                Instant.now().plus(30, ChronoUnit.DAYS),
                "Estadio El Campín",
                "Bogotá",
                "Colombia",
                50000,
                new BigDecimal("350000.00"),
                "COP"
        );
    }

    private Event buildEvent(CreateEventRequest request) {
        return new Event(
                EventId.generate(),
                request.name(),
                request.description(),
                request.eventDate(),
                Venue.of(request.venueName(), request.venueCity(), request.venueCountry()),
                request.totalCapacity(),
                request.totalCapacity(),
                Money.of(request.ticketPrice(), request.currency()),
                Event.EventStatus.DRAFT,
                Instant.now(),
                Instant.now(),
                0L
        );
    }
}
