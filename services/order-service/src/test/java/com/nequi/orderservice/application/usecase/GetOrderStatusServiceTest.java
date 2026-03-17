package com.nequi.orderservice.application.usecase;

import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.orderservice.application.mapper.OrderMapper;
import com.nequi.shared.domain.exception.OrderNotFoundException;
import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.port.OrderRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GetOrderStatusService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetOrderStatusService unit tests")
class GetOrderStatusServiceTest {

    @Mock OrderRepository orderRepository;

    @Spy
    OrderMapper orderMapper = new OrderMapper();

    @InjectMocks GetOrderStatusService getOrderStatusService;

    private static final String ORDER_ID       = "order-001";
    private static final String RESERVATION_ID = "res-123";
    private static final String EVENT_ID       = "event-789";
    private static final String USER_ID        = "user-456";

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return OrderResponse when order is found")
    void shouldReturnOrderWhenFound() {
        // Given
        Instant now = Instant.now();
        Order order = new Order(
                ORDER_ID, RESERVATION_ID, EVENT_ID, USER_ID, 2,
                new BigDecimal("200.00"), "COP",
                OrderStatus.CONFIRMED,
                "idem-key", now, now
        );

        when(orderRepository.findById(ORDER_ID)).thenReturn(Mono.just(order));

        // When & Then
        StepVerifier.create(getOrderStatusService.execute(ORDER_ID))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(ORDER_ID);
                    assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
                    assertThat(response.userId()).isEqualTo(USER_ID);
                    assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
                })
                .verifyComplete();
    }

    // ── Not found ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw OrderNotFoundException when order does not exist")
    void shouldThrowOrderNotFoundExceptionWhenNotFound() {
        // Given
        when(orderRepository.findById(ORDER_ID)).thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(getOrderStatusService.execute(ORDER_ID))
                .expectError(OrderNotFoundException.class)
                .verify();
    }
}
