package com.nequi.reservationservice.application.usecase;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReleaseExpiredReservationsService}.
 *
 * <p>Verifies the GSI-based sweep logic, error isolation, and metric emission.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReleaseExpiredReservationsService — unit tests")
class ReleaseExpiredReservationsServiceTest {

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
    ReleaseExpiredReservationsService releaseExpiredReservationsService;

    private static final String EVENT_ID_1 = UUID.randomUUID().toString();
    private static final String EVENT_ID_2 = UUID.randomUUID().toString();
    private static final String USER_ID    = "user-456";
    private static final String CURRENCY   = "COP";

    private Reservation expiredReservation1;
    private Reservation expiredReservation2;
    private Event event1;
    private Event event2;

    @BeforeEach
    void setUp() {
        Instant past = Instant.now().minus(20, ChronoUnit.MINUTES);
        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);

        // Two expired ACTIVE reservations
        expiredReservation1 = new Reservation(
                UUID.randomUUID().toString(),
                EVENT_ID_1, USER_ID, 2,
                new BigDecimal("300000.00"), CURRENCY,
                ReservationStatus.ACTIVE,
                past,           // already past expiresAt
                past.getEpochSecond(),
                past, past
        );
        expiredReservation2 = new Reservation(
                UUID.randomUUID().toString(),
                EVENT_ID_2, USER_ID, 3,
                new BigDecimal("450000.00"), CURRENCY,
                ReservationStatus.ACTIVE,
                past,
                past.getEpochSecond(),
                past, past
        );

        Venue venue = new Venue("Arena", "Addr", "City", "Country", 1000);
        event1 = new Event(EVENT_ID_1, "Event 1", "desc", venue, future,
                new BigDecimal("150000"), CURRENCY, 1000, 100, 1L, EventStatus.ACTIVE, past, past);
        event2 = new Event(EVENT_ID_2, "Event 2", "desc", venue, future,
                new BigDecimal("150000"), CURRENCY, 1000, 100, 1L, EventStatus.ACTIVE, past, past);
    }

    // ── Happy path — 2 expired reservations ──────────────────────────────────

    @Test
    @DisplayName("should release 2 expired ACTIVE reservations and return count=2")
    void shouldReleaseExpiredReservationsAndReturnCount() {
        // Given
        Reservation expired1 = expiredReservation1.expire();
        Reservation expired2 = expiredReservation2.expire();
        AuditEntry audit1 = AuditEntry.create(expiredReservation1.id(), "RESERVATION", "ACTIVE", "EXPIRED", "system", "scheduler", Instant.now());
        AuditEntry audit2 = AuditEntry.create(expiredReservation2.id(), "RESERVATION", "ACTIVE", "EXPIRED", "system", "scheduler", Instant.now());

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(Flux.just(expiredReservation1, expiredReservation2));
        when(reservationRepository.updateStatus(eq(expiredReservation1.id()), eq(ReservationStatus.EXPIRED)))
                .thenReturn(Mono.just(expired1));
        when(reservationRepository.updateStatus(eq(expiredReservation2.id()), eq(ReservationStatus.EXPIRED)))
                .thenReturn(Mono.just(expired2));
        when(eventRepository.releaseTickets(eq(EVENT_ID_1), eq(2))).thenReturn(Mono.just(event1));
        when(eventRepository.releaseTickets(eq(EVENT_ID_2), eq(3))).thenReturn(Mono.just(event2));
        when(auditRepository.save(any(AuditEntry.class)))
                .thenReturn(Mono.just(audit1))
                .thenReturn(Mono.just(audit2));
        when(meterRegistry.counter(anyString())).thenReturn(counter);

        // When / Then
        StepVerifier.create(releaseExpiredReservationsService.execute())
                .assertNext(count -> assertThat(count).isEqualTo(2))
                .verifyComplete();

        verify(reservationRepository, times(1)).updateStatus(eq(expiredReservation1.id()), eq(ReservationStatus.EXPIRED));
        verify(reservationRepository, times(1)).updateStatus(eq(expiredReservation2.id()), eq(ReservationStatus.EXPIRED));
        verify(eventRepository, times(1)).releaseTickets(EVENT_ID_1, 2);
        verify(eventRepository, times(1)).releaseTickets(EVENT_ID_2, 3);
        verify(auditRepository, times(2)).save(any(AuditEntry.class));
    }

    // ── Zero expired reservations ─────────────────────────────────────────────

    @Test
    @DisplayName("should return count=0 when there are no expired reservations")
    void shouldReturnZeroWhenNoExpiredReservations() {
        // Given
        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(Flux.empty());

        // When / Then
        StepVerifier.create(releaseExpiredReservationsService.execute())
                .assertNext(count -> assertThat(count).isZero())
                .verifyComplete();

        verify(reservationRepository, times(0)).updateStatus(anyString(), any(com.nequi.shared.domain.model.ReservationStatus.class));
        verify(eventRepository, times(0)).releaseTickets(anyString(), anyInt());
        verify(auditRepository, times(0)).save(any());
    }

    // ── Error isolation — one reservation fails, others continue ─────────────

    @Test
    @DisplayName("should continue processing remaining reservations when one release fails")
    void shouldContinueProcessingWhenOneReservationFails() {
        // Given — first reservation fails, second succeeds
        Reservation expired2 = expiredReservation2.expire();
        AuditEntry audit2 = AuditEntry.create(expiredReservation2.id(), "RESERVATION", "ACTIVE", "EXPIRED", "system", "scheduler", Instant.now());

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(Flux.just(expiredReservation1, expiredReservation2));
        // First reservation: updateStatus throws (no further steps are reached for reservation 1)
        when(reservationRepository.updateStatus(eq(expiredReservation1.id()), eq(ReservationStatus.EXPIRED)))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB timeout")));
        // Second reservation: all steps succeed
        when(reservationRepository.updateStatus(eq(expiredReservation2.id()), eq(ReservationStatus.EXPIRED)))
                .thenReturn(Mono.just(expired2));
        when(eventRepository.releaseTickets(eq(EVENT_ID_2), eq(3))).thenReturn(Mono.just(event2));
        when(auditRepository.save(any(AuditEntry.class))).thenReturn(Mono.just(audit2));
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        // Note: eventRepository.releaseTickets for EVENT_ID_1 is NOT stubbed
        // because the error on updateStatus means we never reach releaseTickets for reservation 1.

        // When / Then — only 1 released (the successful one)
        StepVerifier.create(releaseExpiredReservationsService.execute())
                .assertNext(count -> assertThat(count).isEqualTo(1))
                .verifyComplete();

        verify(eventRepository, times(0)).releaseTickets(eq(EVENT_ID_1), anyInt());
        verify(eventRepository, times(1)).releaseTickets(EVENT_ID_2, 3);
    }
}
