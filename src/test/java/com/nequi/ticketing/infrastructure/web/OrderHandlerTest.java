package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.dto.OrderResponse;
import com.nequi.ticketing.application.dto.ReservationResponse;
import com.nequi.ticketing.application.port.in.QueryOrderStatusUseCase;
import com.nequi.ticketing.application.port.in.ReserveTicketsUseCase;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.infrastructure.web.handler.OrderHandler;
import com.nequi.ticketing.infrastructure.web.router.OrderRouter;
import com.nequi.ticketing.shared.error.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest
@Import({OrderRouter.class, OrderHandler.class, GlobalErrorHandler.class})
@DisplayName("OrderHandler HTTP")
class OrderHandlerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean private ReserveTicketsUseCase reserveTicketsUseCase;
    @MockitoBean private QueryOrderStatusUseCase queryOrderStatusUseCase;

    @Test
    @DisplayName("POST /api/v1/orders should return 201")
    void shouldCreateOrder() {
        ReservationResponse response = new ReservationResponse(
                "ord_test123", "evt_abc", "usr_xyz", 2,
                List.of("tkt_001", "tkt_002"),
                Instant.now(), Instant.now().plusSeconds(600), "RESERVED");

        when(reserveTicketsUseCase.execute(any())).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", "test-key-123")
                .bodyValue("""
                        {"eventId":"evt_abc","userId":"usr_xyz","quantity":2,"idempotencyKey":"x"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord_test123")
                .jsonPath("$.status").isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("POST /api/v1/orders should return 400 when missing idempotency key")
    void shouldReturn400WhenMissingIdempotencyKey() {
        webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"eventId":"evt_abc","userId":"usr_xyz","quantity":2,"idempotencyKey":"x"}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("GET /api/v1/orders/{orderId} should return 200")
    void shouldGetOrderStatus() {
        OrderResponse response = new OrderResponse(
                "ord_test123", "evt_abc", "usr_xyz",
                List.of("tkt_001"), 1,
                new BigDecimal("350000"), "COP",
                "CONFIRMED", null, Instant.now(), Instant.now());

        when(queryOrderStatusUseCase.findById("ord_test123")).thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/orders/ord_test123")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("ord_test123")
                .jsonPath("$.status").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("GET /api/v1/orders/{orderId} should return 404 when not found")
    void shouldReturn404WhenOrderNotFound() {
        when(queryOrderStatusUseCase.findById("ord_unknown"))
                .thenReturn(Mono.error(new OrderNotFoundException(OrderId.of("ord_unknown"))));

        webTestClient.get()
                .uri("/api/v1/orders/ord_unknown")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404);
    }
}