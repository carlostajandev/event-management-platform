package com.nequi.reservationservice.application.usecase;

import com.nequi.reservationservice.application.mapper.ReservationMapper;
import com.nequi.shared.domain.exception.ReservationNotFoundException;
import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.ReservationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GetReservationService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetReservationService — unit tests")
class GetReservationServiceTest {

    @Mock
    ReservationRepository reservationRepository;

    @Spy
    ReservationMapper reservationMapper = new ReservationMapper();

    @InjectMocks
    GetReservationService getReservationService;

    private static final String RESERVATION_ID = "res-abc-123";
    private static final String EVENT_ID       = "event-xyz";
    private static final String USER_ID        = "user-456";

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return ReservationResponse when reservation is found")
    void shouldReturnReservationWhenFound() {
        // Given
        Instant now    = Instant.now();
        Instant expiry = now.plus(10, ChronoUnit.MINUTES);

        Reservation reservation = new Reservation(
                RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("300000.00"), "COP",
                ReservationStatus.ACTIVE,
                expiry, expiry.getEpochSecond(),
                now, now
        );

        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(reservation));

        // When & Then
        StepVerifier.create(getReservationService.execute(RESERVATION_ID))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(RESERVATION_ID);
                    assertThat(response.eventId()).isEqualTo(EVENT_ID);
                    assertThat(response.userId()).isEqualTo(USER_ID);
                    assertThat(response.seatsCount()).isEqualTo(2);
                    assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
                    assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("300000.00"));
                })
                .verifyComplete();
    }

    // ── Not found ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw ReservationNotFoundException when reservation does not exist")
    void shouldThrowReservationNotFoundExceptionWhenNotFound() {
        // Given
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(getReservationService.execute(RESERVATION_ID))
                .expectError(ReservationNotFoundException.class)
                .verify();
    }
}
