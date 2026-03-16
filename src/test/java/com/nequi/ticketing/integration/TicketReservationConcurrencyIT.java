package com.nequi.ticketing.integration;

import com.nequi.ticketing.domain.model.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency integration tests — the most critical tests in the project.
 *
 * <p>These tests verify the fundamental correctness guarantee:
 * <b>under concurrent load, exactly N tickets are sold where N = available,
 * never more (no overselling).</b>
 *
 * <p>Infrastructure: LocalStack DynamoDB with conditional writes (optimistic locking).
 * The DynamoDB conditional expression {@code attribute_exists(ticketId) AND #version = :v}
 * ensures that only one concurrent request wins per ticket, regardless of how many
 * simultaneous attempts arrive.
 *
 * <p>Virtual Threads are used for the test client threads to stress-test at higher
 * concurrency without spawning OS threads.
 */
@DisplayName("Ticket Reservation Concurrency")
class TicketReservationConcurrencyIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(TicketReservationConcurrencyIT.class);

    /**
     * THE MOST CRITICAL TEST: 100 concurrent requests for 50 tickets.
     *
     * <p>Exactly 50 must succeed (HTTP 201), exactly 50 must fail (HTTP 409).
     * Zero tickets must be in a corrupted state after the storm.
     *
     * <p>This test proves that DynamoDB conditional writes prevent overselling
     * under real concurrent load — not just unit-test mocks.
     */
    @Test
    @DisplayName("100 concurrent requests for 50 tickets → exactly 50 succeed, 0 overselling")
    void shouldPreventOverselling_under100ConcurrentRequests() throws Exception {
        // ── Arrange ────────────────────────────────────────────────────────────
        final int availableTickets = 50;
        final int concurrentRequests = 100;
        final String eventId = setupEventWithTickets(availableTickets);

        log.info("Starting concurrency test: {} requests for {} tickets, eventId={}",
                concurrentRequests, availableTickets, eventId);

        AtomicInteger successCount  = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount    = new AtomicInteger(0);

        CountDownLatch startGun  = new CountDownLatch(1);
        CountDownLatch allDone   = new CountDownLatch(concurrentRequests);

        // ── Act: 100 Virtual Threads, todos disparando al mismo tiempo ─────────
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < concurrentRequests; i++) {
            final String userId = "usr_concurrent_" + i;
            executor.submit(() -> {
                try {
                    startGun.await(); // espera la señal de salida
                    int status = reserveOneTicket(eventId, userId);
                    if      (status == 201) successCount.incrementAndGet();
                    else if (status == 409) conflictCount.incrementAndGet();
                    else {
                        log.warn("Unexpected status: {}", status);
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Request failed: {}", e.getMessage());
                    errorCount.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
        }

        startGun.countDown(); // ¡todos arrancan a la vez!
        boolean completed = allDone.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // ── Assert ─────────────────────────────────────────────────────────────
        assertTrue(completed, "Timeout: no todos los requests completaron en 60s");

        log.info("Concurrency test result — success={}, conflict={}, error={}",
                successCount.get(), conflictCount.get(), errorCount.get());

        assertThat(successCount.get())
                .as("Exactamente %d reservas deben exitosas", availableTickets)
                .isEqualTo(availableTickets);

        assertThat(conflictCount.get())
                .as("Exactamente %d requests deben recibir 409",
                        concurrentRequests - availableTickets)
                .isEqualTo(concurrentRequests - availableTickets);

        assertThat(errorCount.get())
                .as("Zero errores inesperados")
                .isZero();

        // Verifica estado final de los tickets en DynamoDB
        StepVerifier.create(
                ticketRepository.countByEventIdAndStatus(
                        com.nequi.ticketing.domain.valueobject.EventId.of(eventId),
                        TicketStatus.AVAILABLE)
                .zipWith(ticketRepository.countByEventIdAndStatus(
                        com.nequi.ticketing.domain.valueobject.EventId.of(eventId),
                        TicketStatus.RESERVED)))
                .assertNext(tuple -> {
                    long available = tuple.getT1();
                    long reserved  = tuple.getT2();

                    assertThat(available + reserved)
                            .as("Total de tickets debe seguir siendo %d", availableTickets)
                            .isEqualTo(availableTickets);

                    assertThat(reserved)
                            .as("Exactamente %d tickets deben estar reservados", availableTickets)
                            .isEqualTo(availableTickets);

                    assertThat(available)
                            .as("Cero tickets disponibles después de 50 reservas exitosas")
                            .isZero();
                })
                .verifyComplete();
    }

    /**
     * Verifica que la idempotencia funciona bajo carga concurrente.
     *
     * <p>El mismo idempotencyKey enviado 10 veces concurrentemente debe resultar
     * en exactamente 1 ticket reservado, no 10.
     */
    @Test
    @DisplayName("Idempotency under concurrent duplicate requests — only 1 ticket reserved")
    void shouldHandleIdempotentDuplicates_concurrently() throws Exception {
        final String eventId = setupEventWithTickets(20);
        final String idempotencyKey = UUID.randomUUID().toString();
        final String userId = "usr_idem_concurrent";
        final int duplicateRequests = 10;

        AtomicInteger successCount  = new AtomicInteger(0);
        AtomicInteger errorCount    = new AtomicInteger(0);
        CountDownLatch startGun     = new CountDownLatch(1);
        CountDownLatch allDone      = new CountDownLatch(duplicateRequests);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < duplicateRequests; i++) {
            executor.submit(() -> {
                try {
                    startGun.await();
                    int status = webTestClient.post()
                            .uri("/api/v1/orders")
                            .header("X-Idempotency-Key", idempotencyKey) // mismo key
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "eventId", eventId,
                                    "userId", userId,
                                    "quantity", 1))
                            .exchange()
                            .returnResult(String.class)
                            .getStatus()
                            .value();
                    if (status == 201) successCount.incrementAndGet();
                    else errorCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
        }

        startGun.countDown();
        assertTrue(allDone.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Todos deben responder 201 (el cache devuelve la misma respuesta)
        assertThat(successCount.get()).isEqualTo(duplicateRequests);
        assertThat(errorCount.get()).isZero();

        // Solo 1 ticket reservado — la idempotencia evitó duplicados
        assertTicketCount(eventId, TicketStatus.RESERVED, 1);
        assertTicketCount(eventId, TicketStatus.AVAILABLE, 19);
    }

    /**
     * Verifica que no se puede reservar más de los tickets disponibles
     * incluso con requests secuenciales que superan la capacidad.
     */
    @Test
    @DisplayName("Requests exceeding capacity all fail with 409 after pool is exhausted")
    void shouldReturn409_whenCapacityExhausted() {
        final int ticketCount = 5;
        final String eventId = setupEventWithTickets(ticketCount);

        // Reserva los 5 disponibles
        for (int i = 0; i < ticketCount; i++) {
            int status = reserveOneTicket(eventId, "usr_exhaust_" + i);
            assertThat(status).isEqualTo(201);
        }

        // El siguiente debe fallar con 409
        int overflowStatus = reserveOneTicket(eventId, "usr_overflow");
        assertThat(overflowStatus).isEqualTo(409);

        // Confirma que ningún ticket extra fue reservado
        assertTicketCount(eventId, TicketStatus.RESERVED, ticketCount);
        assertTicketCount(eventId, TicketStatus.AVAILABLE, 0);
    }
}
