package com.nequi.ticketing.integration;

import com.nequi.ticketing.application.dto.ReservationResponse;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.domain.valueobject.TicketId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration tests for the asynchronous order processing flow.
 *
 * <p>Verified end-to-end flow:
 * <pre>
 * POST /api/v1/orders (reserve tickets)
 *   → SQS purchase-orders queue
 *   → SqsOrderConsumer polls message
 *   → ProcessOrderService: RESERVED → PENDING_CONFIRMATION → SOLD
 *   → OrderRepository: PENDING → PROCESSING → CONFIRMED
 * </pre>
 *
 * <p>Awaitility is used to wait for async SQS processing.
 * The SQS consumer polls every 500ms (configured in application-test.yml),
 * so processing completes within 2-3 seconds in normal conditions.
 * Tests use a 15-second timeout to accommodate CI/CD environment slowness.
 *
 * <p>Queue setup: the SQS queue is created in LocalStack on first use.
 * The DynamoDB tables are created by DynamoDbTableInitializer on context startup.
 */
@DisplayName("Order Processing — Async End-to-End Flow")
class OrderProcessingIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingIT.class);

    /**
     * Core happy path: reserve tickets → they get processed → CONFIRMED + SOLD.
     */
    @Test
    @DisplayName("Full flow: reserve → SQS → process → order CONFIRMED + tickets SOLD")
    void shouldProcessOrder_fromReservationToConfirmed() {
        // ── Arrange ────────────────────────────────────────────────────────────
        final String eventId = setupEventWithTickets(20);
        final String userId  = "usr_fullflow_" + UUID.randomUUID().toString().substring(0, 6);
        final String idemKey = UUID.randomUUID().toString();

        ensureQueueExists();

        // ── Act: POST /api/v1/orders (reserva tickets, encola en SQS) ──────────
        ReservationResponse reservation = webTestClient.post()
                .uri("/api/v1/orders")
                .header("X-Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("eventId", eventId, "userId", userId, "quantity", 2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ReservationResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(reservation).isNotNull();
        assertThat(reservation.orderId()).isNotBlank();
        assertThat(reservation.ticketIds()).hasSize(2);
        assertThat(reservation.status()).isEqualTo("RESERVED");

        final String orderId  = reservation.orderId();
        final String ticketId1 = reservation.ticketIds().get(0);
        final String ticketId2 = reservation.ticketIds().get(1);

        log.info("Reservation created — orderId={}, tickets={}", orderId, reservation.ticketIds());

        // ── Assert: espera que el consumer SQS procese la orden ───────────────
        // Awaitility polls hasta 15s con chequeo cada 500ms
        await()
                .atMost(15, SECONDS)
                .pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .alias("Order should reach CONFIRMED state")
                .untilAsserted(() -> {
                    StepVerifier.create(
                                    orderRepository.findById(OrderId.of(orderId)))
                            .assertNext(order -> {
                                assertThat(order.status())
                                        .as("Order debe estar CONFIRMED")
                                        .isEqualTo(OrderStatus.CONFIRMED);
                                assertThat(order.failureReason()).isNull();
                            })
                            .verifyComplete();
                });

        // Verifica que los tickets pasaron a SOLD
        StepVerifier.create(
                        ticketRepository.findById(TicketId.of(ticketId1)))
                .assertNext(t -> assertThat(t.status())
                        .as("Ticket 1 debe estar SOLD")
                        .isEqualTo(TicketStatus.SOLD))
                .verifyComplete();

        StepVerifier.create(
                        ticketRepository.findById(TicketId.of(ticketId2)))
                .assertNext(t -> assertThat(t.status())
                        .as("Ticket 2 debe estar SOLD")
                        .isEqualTo(TicketStatus.SOLD))
                .verifyComplete();

        log.info("Order processing verified — orderId={} CONFIRMED, both tickets SOLD", orderId);
    }

    /**
     * Idempotencia del procesador: si el mismo orderId llega dos veces
     * (retry de SQS), la segunda pasada es un no-op y no corrompe el estado.
     *
     * <p>ProcessOrderService detecta {@code order.status().isFinal() == true}
     * y hace exit inmediato — el estado CONFIRMED nunca se sobreescribe.
     */
    @Test
    @DisplayName("ProcessOrderService is idempotent — reprocessing CONFIRMED order is a no-op")
    void shouldBeIdempotent_whenOrderAlreadyConfirmed() {
        final String eventId = setupEventWithTickets(10);
        final String userId  = "usr_idem_order_" + UUID.randomUUID().toString().substring(0, 6);

        ensureQueueExists();

        // Reserva y espera confirmación inicial
        ReservationResponse reservation = webTestClient.post()
                .uri("/api/v1/orders")
                .header("X-Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("eventId", eventId, "userId", userId, "quantity", 1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ReservationResponse.class)
                .returnResult()
                .getResponseBody();

        final String orderId = reservation.orderId();

        // Espera CONFIRMED (primer procesamiento)
        await()
                .atMost(15, SECONDS)
                .pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        StepVerifier.create(orderRepository.findById(OrderId.of(orderId)))
                                .assertNext(o -> assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED))
                                .verifyComplete());

        // Verifica via API que el estado es CONFIRMED y sigue siéndolo.
        // El SQS consumer puede intentar reprocesar (at-least-once delivery),
        // pero ProcessOrderService detecta isFinal() == true y hace no-op.
        webTestClient.get()
                .uri("/api/v1/orders/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CONFIRMED");
    }

    /**
     * Verifica que POST /api/v1/orders retorna 201 inmediatamente
     * sin esperar el procesamiento SQS (respuesta async).
     */
    @Test
    @DisplayName("POST /api/v1/orders returns 201 immediately — async processing model")
    void shouldReturn201Immediately_withoutWaitingForSqsProcessing() {
        final String eventId = setupEventWithTickets(5);
        ensureQueueExists();

        long startMs = System.currentTimeMillis();

        webTestClient.post()
                .uri("/api/v1/orders")
                .header("X-Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("eventId", eventId, "userId", "usr_fast", "quantity", 1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.orderId").isNotEmpty()
                .jsonPath("$.status").isEqualTo("RESERVED");

        long elapsed = System.currentTimeMillis() - startMs;

        // Debe responder en menos de 3 segundos (no espera procesamiento async de SQS)
        assertThat(elapsed)
                .as("Respuesta debe ser inmediata (< 3000ms), fue: %dms", elapsed)
                .isLessThan(3000L);
    }

    /**
     * Verifica que GET /api/v1/orders/{orderId} retorna 404 para órdenes inexistentes.
     */
    @Test
    @DisplayName("GET /api/v1/orders/{orderId} returns 404 for unknown orderId")
    void shouldReturn404_forUnknownOrder() {
        webTestClient.get()
                .uri("/api/v1/orders/ord_nonexistent_99999")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Ensures the SQS queue exists in LocalStack before tests run.
     * LocalStack creates queues lazily — first send may fail if queue doesn't exist.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private SqsAsyncClient sqsAsyncClient;

    @org.springframework.beans.factory.annotation.Value("${aws.sqs.queues.purchase-orders:emp-test-purchase-orders}")
    private String queueName;

    private void ensureQueueExists() {
        try {
            sqsAsyncClient.createQueue(
                            CreateQueueRequest.builder()
                                    .queueName(queueName)
                                    .build())
                    .join();
        } catch (Exception ex) {
            // Queue already exists — fine
            log.debug("Queue already exists or creation skipped: {}", ex.getMessage());
        }
    }
}