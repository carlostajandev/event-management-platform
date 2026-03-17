package com.nequi.consumerservice.application.usecase;

import com.nequi.shared.domain.exception.OrderNotFoundException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.OrderRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProcessOrderService}.
 *
 * <p>Uses {@link SimpleMeterRegistry} instead of a Mock for MeterRegistry because
 * {@code Timer.start(registry)} calls {@code registry.config().clock()} which requires
 * a real implementation — Mockito mocks return null for {@code config()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessOrderService unit tests")
class ProcessOrderServiceTest {

    @Mock OrderRepository       orderRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock AuditRepository       auditRepository;

    // Use SimpleMeterRegistry — Timer.start() needs a real registry implementation
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC);

    private ProcessOrderService processOrderService;

    private static final String ORDER_ID       = "order-001";
    private static final String RESERVATION_ID = "res-123";
    private static final String EVENT_ID       = "event-789";
    private static final String USER_ID        = "user-456";

    private Order       pendingOrder;
    private Order       confirmedOrder;
    private Reservation activeReservation;
    private Reservation confirmedReservation;

    @BeforeEach
    void setUp() {
        // Manually inject because @InjectMocks doesn't mix with field-initialized mocks
        processOrderService = new ProcessOrderService(
                orderRepository, reservationRepository, auditRepository, meterRegistry, clock);

        Instant now = Instant.now();

        pendingOrder = new Order(
                ORDER_ID, RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                OrderStatus.PENDING_CONFIRMATION,
                "idem-key", now, now
        );

        confirmedOrder = new Order(
                ORDER_ID, RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                OrderStatus.CONFIRMED,
                "idem-key", now, now
        );

        activeReservation = new Reservation(
                RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                ReservationStatus.ACTIVE,
                now.plusSeconds(600), now.plusSeconds(600).getEpochSecond(),
                now, now
        );

        confirmedReservation = new Reservation(
                RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                ReservationStatus.CONFIRMED,
                now.plusSeconds(600), now.plusSeconds(600).getEpochSecond(),
                now, now
        );
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should confirm order and reservation successfully")
    void shouldConfirmOrderSuccessfully() {
        // Given
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Mono.just(pendingOrder));
        when(orderRepository.updateStatus(ORDER_ID, OrderStatus.CONFIRMED))
                .thenReturn(Mono.just(confirmedOrder));
        when(reservationRepository.updateStatus(RESERVATION_ID, ReservationStatus.CONFIRMED))
                .thenReturn(Mono.just(confirmedReservation));
        when(auditRepository.save(any(AuditEntry.class)))
                .thenReturn(Mono.just(AuditEntry.create(ORDER_ID, "ORDER", "PENDING_CONFIRMATION", "CONFIRMED", USER_ID, ORDER_ID, Instant.now())));

        // When & Then
        StepVerifier.create(processOrderService.process(ORDER_ID))
                .verifyComplete();

        verify(orderRepository).updateStatus(ORDER_ID, OrderStatus.CONFIRMED);
        verify(reservationRepository).updateStatus(RESERVATION_ID, ReservationStatus.CONFIRMED);
        // auditRepository.save is called twice: once for ORDER audit, once for RESERVATION audit
        verify(auditRepository, org.mockito.Mockito.times(2)).save(any(AuditEntry.class));
    }

    // ── Idempotency guard ─────────────────────────────────────────────────────

    @Test
    @DisplayName("should return empty Mono without re-processing when order already CONFIRMED")
    void shouldBeIdempotentWhenOrderAlreadyConfirmed() {
        // Given — order is already CONFIRMED (SQS redelivery scenario)
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Mono.just(confirmedOrder));

        // When & Then
        StepVerifier.create(processOrderService.process(ORDER_ID))
                .verifyComplete();

        // Critical: updateStatus must NOT be called on redelivery
        verify(orderRepository, never()).updateStatus(anyString(), any());
        verify(reservationRepository, never()).updateStatus(anyString(), any());
        verify(auditRepository, never()).save(any());
    }

    // ── Order not found ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw OrderNotFoundException when order does not exist")
    void shouldThrowOrderNotFoundExceptionWhenOrderMissing() {
        // Given
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(processOrderService.process(ORDER_ID))
                .expectError(OrderNotFoundException.class)
                .verify();

        verify(orderRepository, never()).updateStatus(anyString(), any());
    }

    // ── Metric increment ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should increment orders.processed.total counter on successful processing")
    void shouldIncrementMetricOnSuccess() {
        // Given
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Mono.just(pendingOrder));
        when(orderRepository.updateStatus(ORDER_ID, OrderStatus.CONFIRMED))
                .thenReturn(Mono.just(confirmedOrder));
        when(reservationRepository.updateStatus(RESERVATION_ID, ReservationStatus.CONFIRMED))
                .thenReturn(Mono.just(confirmedReservation));
        when(auditRepository.save(any(AuditEntry.class)))
                .thenReturn(Mono.just(AuditEntry.create(ORDER_ID, "ORDER", "PENDING_CONFIRMATION", "CONFIRMED", USER_ID, ORDER_ID, Instant.now())));

        // When
        StepVerifier.create(processOrderService.process(ORDER_ID))
                .verifyComplete();

        // Then — verify counter was incremented in SimpleMeterRegistry
        assertThat(meterRegistry.counter("orders.processed.total").count()).isEqualTo(1.0);
    }
}
