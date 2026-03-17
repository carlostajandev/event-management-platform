package com.nequi.reservationservice.application.usecase;

import com.nequi.shared.domain.exception.ReservationNotFoundException;
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
import com.nequi.reservationservice.application.command.CancelReservationCommand;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CancelReservationService}.
 *
 * <p>Covers: successful cancellation, wrong status, wrong userId, and not found.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelReservationService — unit tests")
class CancelReservationServiceTest {

    @Mock
    ReservationRepository reservationRepository;

    @Mock
    EventRepository eventRepository;

    @Mock
    AuditRepository auditRepository;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    Counter counter;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    CancelReservationService cancelReservationService;

    private static final String RESERVATION_ID = UUID.randomUUID().toString();
    private static final String EVENT_ID       = UUID.randomUUID().toString();
    private static final String USER_ID        = "user-789";
    private static final String OTHER_USER_ID  = "user-other";
    private static final String CURRENCY       = "COP";

    private Reservation activeReservation;
    private Event       event;

    @BeforeEach
    void setUp() {
        Instant future = Instant.now().plus(10, ChronoUnit.MINUTES);

        activeReservation = new Reservation(
                RESERVATION_ID,
                EVENT_ID, USER_ID, 2,
                new BigDecimal("300000.00"), CURRENCY,
                ReservationStatus.ACTIVE,
                future,
                future.getEpochSecond(),
                Instant.now(), Instant.now()
        );

        Venue venue = new Venue("Arena", "Addr", "City", "Country", 1000);
        event = new Event(EVENT_ID, "Event", "desc", venue,
                Instant.now().plus(30, ChronoUnit.DAYS),
                new BigDecimal("150000"), CURRENCY,
                1000, 100, 1L, EventStatus.ACTIVE, Instant.now(), Instant.now());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should cancel an ACTIVE reservation successfully")
    void shouldCancelActiveReservationSuccessfully() {
        // Given
        Reservation cancelledReservation = activeReservation.cancel();
        AuditEntry auditEntry = AuditEntry.create(RESERVATION_ID, "RESERVATION", "ACTIVE", "CANCELLED", USER_ID, RESERVATION_ID, Instant.now());

        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(activeReservation));
        when(reservationRepository.updateStatus(eq(RESERVATION_ID), eq(ReservationStatus.CANCELLED)))
                .thenReturn(Mono.just(cancelledReservation));
        when(eventRepository.releaseTickets(eq(EVENT_ID), eq(2))).thenReturn(Mono.just(event));
        when(auditRepository.save(any(AuditEntry.class))).thenReturn(Mono.just(auditEntry));
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        // When / Then
        StepVerifier.create(cancelReservationService.execute(new CancelReservationCommand(RESERVATION_ID, USER_ID)))
                .verifyComplete();

        verify(reservationRepository, times(1)).findById(RESERVATION_ID);
        verify(reservationRepository, times(1)).updateStatus(RESERVATION_ID, ReservationStatus.CANCELLED);
        verify(eventRepository, times(1)).releaseTickets(EVENT_ID, 2);
        verify(auditRepository, times(1)).save(any(AuditEntry.class));
        verify(meterRegistry, times(1)).counter("reservations.cancelled.total");
        verify(counter, times(1)).increment();
    }

    // ── Wrong status ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw IllegalStateException when cancelling a non-ACTIVE reservation")
    void shouldThrowWhenCancellingNonActiveReservation() {
        // Given — reservation is already CANCELLED
        Reservation alreadyCancelled = new Reservation(
                RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("300000.00"), CURRENCY,
                ReservationStatus.CANCELLED,
                Instant.now().plus(10, ChronoUnit.MINUTES),
                Instant.now().plus(10, ChronoUnit.MINUTES).getEpochSecond(),
                Instant.now(), Instant.now()
        );

        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(alreadyCancelled));

        // When / Then
        StepVerifier.create(cancelReservationService.execute(new CancelReservationCommand(RESERVATION_ID, USER_ID)))
                .expectError(IllegalStateException.class)
                .verify();

        verify(reservationRepository, times(0)).updateStatus(anyString(), any(ReservationStatus.class));
        verify(eventRepository, times(0)).releaseTickets(anyString(), anyInt());
        verify(auditRepository, times(0)).save(any());
    }

    // ── Wrong userId ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw IllegalArgumentException when userId does not match reservation owner")
    void shouldThrowWhenUserIdDoesNotMatch() {
        // Given
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(activeReservation));

        // When / Then — OTHER_USER_ID tries to cancel USER_ID's reservation
        StepVerifier.create(cancelReservationService.execute(new CancelReservationCommand(RESERVATION_ID, OTHER_USER_ID)))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(reservationRepository, times(0)).updateStatus(anyString(), any(ReservationStatus.class));
        verify(eventRepository, times(0)).releaseTickets(anyString(), anyInt());
        verify(auditRepository, times(0)).save(any());
    }

    // ── Not found ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw ReservationNotFoundException when reservation does not exist")
    void shouldThrowReservationNotFoundExceptionWhenNotFound() {
        // Given
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(cancelReservationService.execute(new CancelReservationCommand(RESERVATION_ID, USER_ID)))
                .expectError(ReservationNotFoundException.class)
                .verify();

        verify(reservationRepository, times(0)).updateStatus(anyString(), any(ReservationStatus.class));
        verify(eventRepository, times(0)).releaseTickets(anyString(), anyInt());
    }
}
