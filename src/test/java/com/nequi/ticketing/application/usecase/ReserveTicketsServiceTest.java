package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.ReservationResponse;
import com.nequi.ticketing.application.dto.ReserveTicketsCommand;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.TicketNotAvailableException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.repository.IdempotencyRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.service.TicketStateMachine;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.TicketId;
import com.nequi.ticketing.domain.valueobject.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReserveTicketsService")
class ReserveTicketsServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private IdempotencyRepository idempotencyRepository;

    private ReserveTicketsService service;

    @BeforeEach
    void setUp() {
        TicketStateMachine stateMachine = new TicketStateMachine();
        tools.jackson.databind.ObjectMapper objectMapper =
                tools.jackson.databind.json.JsonMapper.builder()
                        .findAndAddModules()
                        .build();

        service = new ReserveTicketsService(
                eventRepository, ticketRepository, idempotencyRepository,
                stateMachine, 10, 24, objectMapper);
    }

    @Test
    @DisplayName("should reserve tickets successfully")
    void shouldReserveTicketsSuccessfully() {
        ReserveTicketsCommand command = validCommand(2);
        Event event = validEvent();
        Ticket ticket1 = availableTicket(command.eventId());
        Ticket ticket2 = availableTicket(command.eventId());

        // New flow: claim key first (save returns empty = claimed successfully)
        when(idempotencyRepository.save(any(), anyString(), anyInt()))
                .thenReturn(Mono.empty());
        when(idempotencyRepository.updateResponse(any(), anyString()))
                .thenReturn(Mono.empty());
        when(eventRepository.findById(any())).thenReturn(Mono.just(event));
        when(ticketRepository.findAvailableByEventId(eq(EventId.of("evt_test123")), eq(1)))
                .thenAnswer(new Answer<Flux<Ticket>>() {
                    private int callCount = 0;

                    @Override
                    public Flux<Ticket> answer(InvocationOnMock invocation) {
                        callCount++;
                        if (callCount == 1) return Flux.just(ticket1);
                        if (callCount == 2) return Flux.just(ticket2);
                        return Flux.error(new RuntimeException("Unexpected call #" + callCount));
                    }
                });
        when(ticketRepository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.execute(command))
                .assertNext(response -> {
                    assertThat(response.orderId()).startsWith("ord_");
                    assertThat(response.quantity()).isEqualTo(2);
                    assertThat(response.ticketIds()).hasSize(2);
                    assertThat(response.status()).isEqualTo("RESERVED");
                    assertThat(response.expiresAt()).isAfter(response.reservedAt());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should return cached response on duplicate idempotency key")
    void shouldReturnCachedResponseOnDuplicate() throws Exception {
        ReserveTicketsCommand command = validCommand(1);
        tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        ReservationResponse cached = new ReservationResponse(
                "ord_cached", command.eventId(), command.userId(), 1,
                List.of("tkt_123"), Instant.now(), Instant.now().plusSeconds(600), "RESERVED");
        String cachedJson = mapper.writeValueAsString(cached);

        // Claim fails with ConditionalCheckFailed = another request already owns this key
        when(idempotencyRepository.save(any(), anyString(), anyInt()))
                .thenReturn(Mono.error(ConditionalCheckFailedException.builder()
                        .message("The conditional request failed").build()));
        when(idempotencyRepository.findResponse(any())).thenReturn(Mono.just(cachedJson));

        StepVerifier.create(service.execute(command))
                .assertNext(response -> {
                    assertThat(response.orderId()).isEqualTo("ord_cached");
                    assertThat(response.ticketIds()).containsExactly("tkt_123");
                })
                .verifyComplete();

        // Must NOT process a new reservation
        verify(eventRepository, never()).findById(any());
        verify(ticketRepository, never()).update(any());
    }

    @Test
    @DisplayName("should fail with TicketNotAvailableException when not enough tickets")
    void shouldFailWhenNotEnoughTickets() {
        ReserveTicketsCommand command = validCommand(5);
        Event event = validEvent();

        // Claim succeeds — this request owns the key
        when(idempotencyRepository.save(any(), anyString(), anyInt()))
                .thenReturn(Mono.empty());
        when(eventRepository.findById(any())).thenReturn(Mono.just(event));
        when(ticketRepository.findAvailableByEventId(any(), eq(1)))
                .thenReturn(Flux.empty()); // no tickets available

        StepVerifier.create(service.execute(command))
                .expectError(TicketNotAvailableException.class)
                .verify();
    }

    @Test
    @DisplayName("should fail with EventNotFoundException for unknown event")
    void shouldFailForUnknownEvent() {
        ReserveTicketsCommand command = validCommand(1);

        // Claim succeeds — then event lookup fails
        when(idempotencyRepository.save(any(), anyString(), anyInt()))
                .thenReturn(Mono.empty());
        when(eventRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.execute(command))
                .expectError(EventNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("should handle concurrent modification and propagate as TicketNotAvailable")
    void shouldHandleConcurrentModification() {
        ReserveTicketsCommand command = validCommand(1);
        Event event = validEvent();
        Ticket available = availableTicket(command.eventId());

        // Claim succeeds — this request owns the key
        when(idempotencyRepository.save(any(), anyString(), anyInt()))
                .thenReturn(Mono.empty());
        when(eventRepository.findById(any())).thenReturn(Mono.just(event));
        when(ticketRepository.findAvailableByEventId(any(), eq(1)))
                .thenReturn(Flux.just(available));
        // Every update attempt fails with concurrent modification
        when(ticketRepository.update(any()))
                .thenReturn(Mono.error(
                        new RuntimeException("Concurrent modification on ticket: tkt_test")));

        StepVerifier.create(service.execute(command))
                .expectError(TicketNotAvailableException.class)
                .verify();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ReserveTicketsCommand validCommand(int quantity) {
        return new ReserveTicketsCommand(
                "evt_test123",
                "usr_abc456",
                quantity,
                UUID.randomUUID().toString()
        );
    }

    private Event validEvent() {
        return new Event(
                EventId.of("evt_test123"),
                "Test Event", "desc",
                Instant.now().plus(30, ChronoUnit.DAYS),
                Venue.of("Venue", "City", "CO"),
                100, 100,
                Money.ofCOP(new BigDecimal("350000")),
                Event.EventStatus.PUBLISHED,
                Instant.now(), Instant.now(), 0L
        );
    }

    private Ticket availableTicket(String eventId) {
        return new Ticket(
                TicketId.generate(),
                EventId.of(eventId),
                null, null,
                TicketStatus.AVAILABLE,
                Money.ofCOP(new BigDecimal("350000")),
                null, null, null,
                Instant.now(), Instant.now(), 0L
        );
    }
}