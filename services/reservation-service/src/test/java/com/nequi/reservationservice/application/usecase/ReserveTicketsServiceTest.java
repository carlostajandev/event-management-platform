package com.nequi.reservationservice.application.usecase;

import com.nequi.reservationservice.application.command.ReserveTicketsCommand;
import com.nequi.reservationservice.application.dto.ReservationResponse;
import com.nequi.shared.domain.exception.ConcurrentModificationException;
import com.nequi.shared.domain.exception.EventNotFoundException;
import com.nequi.shared.domain.exception.TicketNotAvailableException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.model.Venue;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.EventRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.nequi.reservationservice.application.mapper.ReservationMapper;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReserveTicketsService}.
 *
 * <p>Covers the happy path, all error branches, and retry logic.
 * All dependencies are mocked — no DynamoDB involved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReserveTicketsService — unit tests")
class ReserveTicketsServiceTest {

    @Mock
    EventRepository eventRepository;

    @Mock
    ReservationRepository reservationRepository;

    @Mock
    AuditRepository auditRepository;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter counter;

    @Spy
    ReservationMapper reservationMapper = new ReservationMapper();

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    ReserveTicketsService reserveTicketsService;

    private static final String EVENT_ID    = UUID.randomUUID().toString();
    private static final String USER_ID     = "user-123";
    private static final String CURRENCY    = "COP";
    private static final BigDecimal PRICE   = new BigDecimal("150000.00");

    private Event activeEvent;
    private ReserveTicketsCommand validRequest;

    @BeforeEach
    void setUp() {
        Venue venue = new Venue("Movistar Arena", "Calle 26", "Bogotá", "Colombia", 20_000);
        activeEvent = new Event(
                EVENT_ID, "Feid Live 2026", "Concert",
                venue,
                Instant.now().plus(30, ChronoUnit.DAYS),
                PRICE,
                CURRENCY,
                10_000,   // totalCapacity
                10,       // availableCount — enough for 2 seats
                1L,       // version
                EventStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );

        validRequest = new ReserveTicketsCommand(EVENT_ID, USER_ID, 2);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should reserve tickets successfully and return ACTIVE ReservationResponse")
    void shouldReserveTicketsSuccessfully() {
        // Given
        Event updatedEvent = activeEvent.reserveTickets(2);   // decrements availableCount
        Reservation savedReservation = Reservation.create(
                UUID.randomUUID().toString(),
                EVENT_ID, USER_ID, 2,
                PRICE.multiply(BigDecimal.valueOf(2)),
                CURRENCY,
                Instant.now()
        );
        AuditEntry auditEntry = AuditEntry.create(savedReservation.id(), "RESERVATION", "NONE", "ACTIVE", USER_ID, "test", Instant.now());

        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(activeEvent));
        when(eventRepository.reserveTickets(eq(EVENT_ID), eq(2), eq(1L))).thenReturn(Mono.just(updatedEvent));
        when(reservationRepository.save(any(Reservation.class))).thenReturn(Mono.just(savedReservation));
        when(auditRepository.save(any(AuditEntry.class))).thenReturn(Mono.just(auditEntry));
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        // When / Then
        StepVerifier.create(reserveTicketsService.execute(validRequest))
                .assertNext(response -> {
                    assertThat(response).isNotNull();
                    assertThat(response.eventId()).isEqualTo(EVENT_ID);
                    assertThat(response.userId()).isEqualTo(USER_ID);
                    assertThat(response.seatsCount()).isEqualTo(2);
                    assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
                    assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("300000.00"));
                    assertThat(response.currency()).isEqualTo(CURRENCY);
                    assertThat(response.expiresAt()).isNotNull();
                    assertThat(response.createdAt()).isNotNull();
                })
                .verifyComplete();

        verify(eventRepository, times(1)).findById(EVENT_ID);
        verify(eventRepository, times(1)).reserveTickets(EVENT_ID, 2, 1L);
        verify(reservationRepository, times(1)).save(any(Reservation.class));
        verify(auditRepository, times(1)).save(any(AuditEntry.class));
        verify(meterRegistry, times(1)).counter("tickets.reserved.total");
        verify(counter, times(1)).increment();
    }

    // ── EventNotFoundException ────────────────────────────────────────────────

    @Test
    @DisplayName("should throw EventNotFoundException when event does not exist")
    void shouldThrowEventNotFoundExceptionWhenEventNotFound() {
        // Given
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(reserveTicketsService.execute(validRequest))
                .expectError(EventNotFoundException.class)
                .verify();

        verify(eventRepository, times(1)).findById(EVENT_ID);
        verify(eventRepository, times(0)).reserveTickets(anyString(), anyInt(), anyLong());
        verify(reservationRepository, times(0)).save(any());
    }

    // ── TicketNotAvailableException ───────────────────────────────────────────

    @Test
    @DisplayName("should throw TicketNotAvailableException when event has no available tickets")
    void shouldThrowTicketNotAvailableExceptionWhenNoTickets() {
        // Given — event with only 1 available ticket; request is for 2
        Venue venue = new Venue("Arena", "Addr", "City", "Country", 100);
        Event soldOutEvent = new Event(
                EVENT_ID, "Sold Out Event", "desc",
                venue,
                Instant.now().plus(30, ChronoUnit.DAYS),
                PRICE, CURRENCY,
                100,
                1,    // only 1 ticket left
                1L,
                EventStatus.ACTIVE,
                Instant.now(),
                Instant.now()
        );

        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(soldOutEvent));

        // When / Then
        StepVerifier.create(reserveTicketsService.execute(validRequest))  // requests 2 seats
                .expectError(TicketNotAvailableException.class)
                .verify();

        verify(eventRepository, times(0)).reserveTickets(anyString(), anyInt(), anyLong());
        verify(reservationRepository, times(0)).save(any());
    }

    // ── ConcurrentModification — retry succeeds on 2nd attempt ───────────────

    @Test
    @DisplayName("should retry on ConcurrentModificationException and succeed on second attempt")
    void shouldRetryOnConcurrentModificationAndSucceedEventually() {
        // Given — first call throws ConcurrentModificationException, second succeeds
        Event updatedEvent = activeEvent.reserveTickets(2);
        Reservation savedReservation = Reservation.create(
                UUID.randomUUID().toString(),
                EVENT_ID, USER_ID, 2,
                PRICE.multiply(BigDecimal.valueOf(2)),
                CURRENCY,
                Instant.now()
        );
        AuditEntry auditEntry = AuditEntry.create(savedReservation.id(), "RESERVATION", "NONE", "ACTIVE", USER_ID, "test", Instant.now());

        when(eventRepository.findById(EVENT_ID))
                .thenReturn(Mono.just(activeEvent));
        when(eventRepository.reserveTickets(eq(EVENT_ID), eq(2), eq(1L)))
                .thenReturn(Mono.error(new ConcurrentModificationException(EVENT_ID)))
                .thenReturn(Mono.just(updatedEvent));  // succeeds on retry
        when(reservationRepository.save(any(Reservation.class))).thenReturn(Mono.just(savedReservation));
        when(auditRepository.save(any(AuditEntry.class))).thenReturn(Mono.just(auditEntry));
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        // When / Then
        StepVerifier.create(reserveTicketsService.execute(validRequest))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
                    assertThat(response.seatsCount()).isEqualTo(2);
                })
                .verifyComplete();

        // reserveTickets called twice: 1 failure + 1 success (but findById called once due to retry on flatMap)
        verify(eventRepository, times(2)).reserveTickets(eq(EVENT_ID), eq(2), eq(1L));
    }

    // ── ConcurrentModification — all retries exhausted ────────────────────────

    @Test
    @DisplayName("should propagate ConcurrentModificationException after max retries exhausted")
    void shouldFailAfterMaxRetriesOnConcurrentModification() {
        // Given — all calls throw ConcurrentModificationException (3 retries = 4 total calls)
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(activeEvent));
        when(eventRepository.reserveTickets(eq(EVENT_ID), eq(2), eq(1L)))
                .thenReturn(Mono.error(new ConcurrentModificationException(EVENT_ID)));

        // When / Then
        StepVerifier.create(reserveTicketsService.execute(validRequest))
                .expectError(ConcurrentModificationException.class)
                .verify();

        // 1 initial attempt + 3 retries = 4 total
        verify(eventRepository, times(4)).reserveTickets(eq(EVENT_ID), eq(2), eq(1L));
        verify(reservationRepository, times(0)).save(any());
        verify(auditRepository, times(0)).save(any());
    }

    // ── BigDecimal total amount precision ─────────────────────────────────────

    @Test
    @DisplayName("should calculate totalAmount as ticketPrice * seatsCount using BigDecimal")
    void shouldCalculateTotalAmountCorrectly() {
        // Given — 3 seats at 150,000 COP = 450,000 COP
        ReserveTicketsCommand requestFor3 = new ReserveTicketsCommand(EVENT_ID, USER_ID, 3);
        Event updatedEvent = activeEvent.reserveTickets(3);
        Reservation savedReservation = Reservation.create(
                UUID.randomUUID().toString(),
                EVENT_ID, USER_ID, 3,
                PRICE.multiply(BigDecimal.valueOf(3)),   // 450,000
                CURRENCY,
                Instant.now()
        );
        AuditEntry auditEntry = AuditEntry.create(savedReservation.id(), "RESERVATION", "NONE", "ACTIVE", USER_ID, "test", Instant.now());

        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(activeEvent));
        when(eventRepository.reserveTickets(eq(EVENT_ID), eq(3), eq(1L))).thenReturn(Mono.just(updatedEvent));
        when(reservationRepository.save(any(Reservation.class))).thenReturn(Mono.just(savedReservation));
        when(auditRepository.save(any(AuditEntry.class))).thenReturn(Mono.just(auditEntry));
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        StepVerifier.create(reserveTicketsService.execute(requestFor3))
                .assertNext(response ->
                        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("450000.00"))
                )
                .verifyComplete();
    }
}
