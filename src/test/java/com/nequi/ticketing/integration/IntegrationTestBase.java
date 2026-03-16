package com.nequi.ticketing.integration;

import com.nequi.ticketing.TestcontainersConfiguration;
import com.nequi.ticketing.application.dto.CreateEventRequest;
import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired protected WebTestClient webTestClient;
    @Autowired protected EventRepository eventRepository;
    @Autowired protected TicketRepository ticketRepository;
    @Autowired protected OrderRepository orderRepository;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Value("${aws.sqs.queues.purchase-orders:emp-test-purchase-orders}")
    private String queueName;

    /**
     * Ensures SQS queues exist before every test.
     *
     * LocalStack creates queues lazily. Without this, the first message sent
     * during a test can be lost silently if the queue doesn't exist yet,
     * causing Awaitility to time out waiting for a processing that never happens.
     *
     * Moving this to @BeforeEach guarantees the queue is ready before the
     * SqsOrderConsumer's first poll cycle in every test.
     */
    @BeforeEach
    void ensureInfrastructureReady() {
        try {
            sqsAsyncClient.createQueue(
                            CreateQueueRequest.builder()
                                    .queueName(queueName)
                                    .build())
                    .join();
        } catch (Exception ex) {
            // Queue already exists after the first test — expected
        }
        try {
            sqsAsyncClient.createQueue(
                            CreateQueueRequest.builder()
                                    .queueName(queueName + "-dlq")
                                    .build())
                    .join();
        } catch (Exception ex) {
            // ignore
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    protected String createEventViaApi(int capacity) {
        CreateEventRequest request = new CreateEventRequest(
                "Test Event " + UUID.randomUUID().toString().substring(0, 8),
                "Integration test event",
                Instant.now().plus(30, ChronoUnit.DAYS),
                "Test Arena",
                "Medellín",
                "CO",
                capacity,
                new BigDecimal("150.00"),
                "COP"
        );

        return webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventResponse.class)
                .returnResult()
                .getResponseBody()
                .eventId();
    }

    protected void createTicketsForEvent(String eventId, int count) {
        EventId id = EventId.of(eventId);
        Flux.range(0, count)
                .flatMap(i -> ticketRepository.save(
                        Ticket.createAvailable(id,
                                com.nequi.ticketing.domain.valueobject.Money.of(
                                        new BigDecimal("150.00"), "COP"))))
                .blockLast(java.time.Duration.ofSeconds(30));
    }

    protected String setupEventWithTickets(int ticketCount) {
        String eventId = createEventViaApi(ticketCount);
        createTicketsForEvent(eventId, ticketCount);
        return eventId;
    }

    protected long countTickets(String eventId, TicketStatus status) {
        return ticketRepository
                .countByEventIdAndStatus(EventId.of(eventId), status)
                .block(java.time.Duration.ofSeconds(10));
    }

    protected int reserveOneTicket(String eventId, String userId) {
        return webTestClient.post()
                .uri("/api/v1/orders")
                .header("X-Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "eventId", eventId,
                        "userId", userId,
                        "quantity", 1))
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
    }

    protected void assertTicketCount(String eventId, TicketStatus status, long expected) {
        StepVerifier.create(
                        ticketRepository.countByEventIdAndStatus(EventId.of(eventId), status))
                .expectNext(expected)
                .verifyComplete();
    }
}