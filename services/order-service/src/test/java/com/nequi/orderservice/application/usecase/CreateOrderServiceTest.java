package com.nequi.orderservice.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nequi.orderservice.application.command.CreateOrderCommand;
import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.shared.domain.exception.ReservationNotFoundException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.IdempotencyRecord;
import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.IdempotencyRepository;
import com.nequi.shared.domain.port.OrderRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.nequi.orderservice.application.mapper.OrderMapper;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
 * Unit tests for {@link CreateOrderService}.
 *
 * <p>Validates the critical paths:
 * <ul>
 *   <li>Successful order creation with atomic write</li>
 *   <li>Idempotency cache hit — returns cached response without hitting DynamoDB</li>
 *   <li>Reservation not found — propagates ReservationNotFoundException</li>
 *   <li>Reservation not ACTIVE — rejects with IllegalStateException</li>
 *   <li>UserId mismatch — rejects with IllegalArgumentException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CreateOrderService unit tests")
class CreateOrderServiceTest {

    @Mock ReservationRepository reservationRepository;
    @Mock OrderRepository       orderRepository;
    @Mock IdempotencyRepository idempotencyRepository;
    @Mock AuditRepository       auditRepository;
    @Mock ObjectMapper          objectMapper;
    @Mock MeterRegistry         meterRegistry;
    @Mock Counter               counter;

    @Spy
    OrderMapper orderMapper = new OrderMapper();

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks CreateOrderService createOrderService;

    private static final String RESERVATION_ID   = "res-123";
    private static final String USER_ID          = "user-456";
    private static final String EVENT_ID         = "event-789";
    private static final String IDEMPOTENCY_KEY  = "idem-key-abc";
    private static final String ORDER_ID         = "order-001";

    private Reservation activeReservation;
    private Order       pendingOrder;
    private OrderResponse orderResponse;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();

        activeReservation = new Reservation(
                RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                ReservationStatus.ACTIVE,
                now.plusSeconds(600), now.plusSeconds(600).getEpochSecond(),
                now, now
        );

        pendingOrder = new Order(
                ORDER_ID, RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                OrderStatus.PENDING_CONFIRMATION,
                IDEMPOTENCY_KEY, now, now
        );

        orderResponse = new OrderResponse(
                ORDER_ID, RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                OrderStatus.PENDING_CONFIRMATION, now, now
        );

        // Default MeterRegistry behavior
        when(meterRegistry.counter(anyString())).thenReturn(counter);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should create order successfully when reservation is ACTIVE and userId matches")
    void shouldCreateOrderSuccessfully() throws JsonProcessingException {
        // Given
        CreateOrderCommand request = new CreateOrderCommand(RESERVATION_ID, USER_ID, IDEMPOTENCY_KEY);

        when(idempotencyRepository.findByKey(IDEMPOTENCY_KEY))
                .thenReturn(Mono.empty());  // No cached response — first time
        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Mono.just(activeReservation));
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"id\":\"order-001\"}");
        when(orderRepository.saveWithOutbox(any(Order.class), anyString()))
                .thenReturn(Mono.just(pendingOrder));
        when(idempotencyRepository.save(any(IdempotencyRecord.class)))
                .thenReturn(Mono.just(IdempotencyRecord.create(IDEMPOTENCY_KEY, ORDER_ID, "{}")));
        when(auditRepository.save(any(AuditEntry.class)))
                .thenReturn(Mono.just(AuditEntry.create(ORDER_ID, "ORDER", "NONE",
                        "PENDING_CONFIRMATION", USER_ID, IDEMPOTENCY_KEY, Instant.now())));

        // When & Then
        StepVerifier.create(createOrderService.execute(request))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                    assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
                    assertThat(response.userId()).isEqualTo(USER_ID);
                })
                .verifyComplete();

        verify(orderRepository).saveWithOutbox(any(Order.class), anyString());
        verify(idempotencyRepository).save(any(IdempotencyRecord.class));
        verify(auditRepository).save(any(AuditEntry.class));
        verify(counter).increment();
    }

    // ── Idempotency cache hit ─────────────────────────────────────────────────

    @Test
    @DisplayName("should return cached response for duplicate request without calling orderRepository")
    void shouldReturnCachedResponseForDuplicateRequest() throws JsonProcessingException {
        // Given
        CreateOrderCommand request = new CreateOrderCommand(RESERVATION_ID, USER_ID, IDEMPOTENCY_KEY);
        IdempotencyRecord cachedRecord = IdempotencyRecord.create(IDEMPOTENCY_KEY, ORDER_ID, "{\"id\":\"order-001\"}");

        when(idempotencyRepository.findByKey(IDEMPOTENCY_KEY))
                .thenReturn(Mono.just(cachedRecord));
        when(objectMapper.readValue(anyString(), any(Class.class)))
                .thenReturn(orderResponse);

        // When & Then
        StepVerifier.create(createOrderService.execute(request))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(ORDER_ID);
                    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                })
                .verifyComplete();

        // Critical: orderRepository must NOT be called on duplicate
        verify(orderRepository, never()).saveWithOutbox(any(), any());
        verify(auditRepository, never()).save(any());
    }

    // ── Reservation not found ─────────────────────────────────────────────────

    @Test
    @DisplayName("should throw ReservationNotFoundException when reservation does not exist")
    void shouldThrowReservationNotFoundExceptionWhenReservationMissing() {
        // Given
        CreateOrderCommand request = new CreateOrderCommand(RESERVATION_ID, USER_ID, IDEMPOTENCY_KEY);

        when(idempotencyRepository.findByKey(IDEMPOTENCY_KEY))
                .thenReturn(Mono.empty());
        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Mono.empty());  // Not found

        // When & Then
        StepVerifier.create(createOrderService.execute(request))
                .expectError(ReservationNotFoundException.class)
                .verify();

        verify(orderRepository, never()).saveWithOutbox(any(), any());
    }

    // ── Reservation not ACTIVE ────────────────────────────────────────────────

    @Test
    @DisplayName("should throw IllegalStateException when reservation status is not ACTIVE")
    void shouldThrowIllegalStateExceptionWhenReservationNotActive() {
        // Given
        CreateOrderCommand request = new CreateOrderCommand(RESERVATION_ID, USER_ID, IDEMPOTENCY_KEY);
        Instant now = Instant.now();
        Reservation confirmedReservation = new Reservation(
                RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                ReservationStatus.CONFIRMED,  // Not ACTIVE
                now.plusSeconds(600), now.plusSeconds(600).getEpochSecond(),
                now, now
        );

        when(idempotencyRepository.findByKey(IDEMPOTENCY_KEY))
                .thenReturn(Mono.empty());
        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Mono.just(confirmedReservation));

        // When & Then
        StepVerifier.create(createOrderService.execute(request))
                .expectError(IllegalStateException.class)
                .verify();

        verify(orderRepository, never()).saveWithOutbox(any(), any());
    }

    // ── UserId mismatch ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw IllegalArgumentException when userId does not match reservation owner")
    void shouldThrowIllegalArgumentExceptionWhenUserIdMismatch() {
        // Given
        CreateOrderCommand request = new CreateOrderCommand(RESERVATION_ID, "different-user", IDEMPOTENCY_KEY);

        when(idempotencyRepository.findByKey(IDEMPOTENCY_KEY))
                .thenReturn(Mono.empty());
        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Mono.just(activeReservation));  // reservation.userId() == USER_ID

        // When & Then
        StepVerifier.create(createOrderService.execute(request))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(orderRepository, never()).saveWithOutbox(any(), any());
    }
}
