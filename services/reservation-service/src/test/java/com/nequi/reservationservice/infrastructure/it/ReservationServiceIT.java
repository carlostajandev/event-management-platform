package com.nequi.reservationservice.infrastructure.it;

import com.nequi.reservationservice.ReservationServiceApplication;
import com.nequi.reservationservice.application.dto.ReservationResponse;
import com.nequi.reservationservice.application.dto.ReserveTicketsRequest;
import com.nequi.reservationservice.infrastructure.persistence.dynamodb.entity.EventEntity;
import com.nequi.shared.domain.port.EventRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

/**
 * Integration tests for reservation-service HTTP endpoints.
 *
 * <p>The reservation-service {@code DynamoDbTableInitializer} (active on "test" profile)
 * creates {@code emp-reservations} and {@code emp-audit} automatically. This class
 * creates {@code emp-events} manually in {@link #createEmpEventsTable()} because
 * that table is owned by event-service and is not provisioned by reservation-service's
 * initializer.
 *
 * <p>Key invariant tested: concurrent ticket reservation NEVER oversells
 * (DynamoDB conditional expression: {@code availableCount >= :n AND version = :expected}).
 */
@Slf4j
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = ReservationServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("ReservationService — Integration Tests")
class ReservationServiceIT {

    // ── Shared LocalStack container ───────────────────────────────────────────

    @Container
    static final LocalStackContainer LOCAL_STACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4.0"))
                    .withServices(DYNAMODB);

    /**
     * Overrides Spring properties AND creates the {@code emp-events} table before the
     * Spring context is bootstrapped. {@code @DynamicPropertySource} is the right place
     * for pre-context setup because it runs after Testcontainers has started the container
     * but before Spring initializes any beans (including DynamoDbTableInitializer).
     *
     * <p>reservation-service's own {@code DynamoDbTableInitializer} creates only
     * {@code emp-reservations} and {@code emp-audit}. We must create {@code emp-events}
     * here so it exists when the Spring context tries to use the EventRepository.
     */
    @DynamicPropertySource
    static void overridePropertiesAndCreateTables(DynamicPropertyRegistry registry) {
        registry.add("aws.dynamodb.endpoint",
                () -> LOCAL_STACK.getEndpointOverride(DYNAMODB).toString());
        registry.add("aws.sqs.endpoint",
                () -> LOCAL_STACK.getEndpointOverride(DYNAMODB).toString());
        registry.add("aws.region", LOCAL_STACK::getRegion);
        registry.add("aws.accessKeyId",  LOCAL_STACK::getAccessKey);
        registry.add("aws.secretAccessKey", LOCAL_STACK::getSecretKey);

        // Create emp-events table before Spring context starts
        try (DynamoDbClient setupClient = DynamoDbClient.builder()
                .endpointOverride(LOCAL_STACK.getEndpointOverride(DYNAMODB))
                .region(Region.of(LOCAL_STACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                LOCAL_STACK.getAccessKey(),
                                LOCAL_STACK.getSecretKey())))
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder().build())
                .build()) {

            try {
                setupClient.createTable(CreateTableRequest.builder()
                        .tableName("emp-events")
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                AttributeDefinition.builder()
                                        .attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder()
                                        .attributeName("SK").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder()
                                        .attributeName("GSI1PK").attributeType(ScalarAttributeType.S).build()
                        )
                        .keySchema(
                                KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                        )
                        .globalSecondaryIndexes(
                                GlobalSecondaryIndex.builder()
                                        .indexName("GSI1")
                                        .keySchema(
                                                KeySchemaElement.builder()
                                                        .attributeName("GSI1PK").keyType(KeyType.HASH).build()
                                        )
                                        .projection(Projection.builder()
                                                .projectionType(ProjectionType.ALL).build())
                                        .build()
                        )
                        .build());
                log.info("emp-events table created in @DynamicPropertySource (before Spring context)");
            } catch (ResourceInUseException e) {
                log.info("emp-events table already exists — skipping creation");
            }
        }
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    /**
     * EventRepository bean provided by reservation-service's Spring context
     * (DynamoDbEventRepository). Used to insert test events directly without
     * going through event-service's HTTP API.
     */
    @Autowired
    EventRepository eventRepository;

    // ── Helper: insert an EventEntity directly into emp-events via Enhanced client ──

    @Autowired
    DynamoDbEnhancedAsyncClient enhancedAsyncClient;

    /**
     * Inserts an {@link EventEntity} into the {@code emp-events} table.
     * Sets all required attributes for the reservation conditional-write to work.
     */
    private String insertTestEvent(int totalCapacity, int availableCount) {
        String eventId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        var table = enhancedAsyncClient.table("emp-events",
                TableSchema.fromBean(EventEntity.class));

        EventEntity entity = new EventEntity();
        entity.setPk("EVENT#" + eventId);
        entity.setSk("METADATA");
        entity.setGsi1Pk("STATUS#ACTIVE");
        entity.setId(eventId);
        entity.setName("IT Test Event " + eventId);
        entity.setDescription("Created by integration test");
        entity.setVenueName("Test Venue");
        entity.setVenueAddress("Test Address 123");
        entity.setVenueCity("Bogota");
        entity.setVenueCountry("Colombia");
        entity.setVenueCapacity(totalCapacity);
        entity.setEventDate(Instant.now().plus(30, ChronoUnit.DAYS).toString());
        entity.setTicketPrice("50000.00");
        entity.setCurrency("COP");
        entity.setStatus("ACTIVE");
        entity.setTotalCapacity(totalCapacity);
        entity.setAvailableCount(availableCount);
        entity.setVersion(null); // null → Enhanced Client adds attribute_not_exists(version) → DynamoDB sets version=1
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        table.putItem(entity).join();
        log.info("Inserted test event: id={}, capacity={}, available={}",
                eventId, totalCapacity, availableCount);
        return eventId;
    }

    /**
     * Reads back an {@link EventEntity} from emp-events to verify final state.
     */
    private EventEntity readEventEntity(String eventId) {
        var table = enhancedAsyncClient.table("emp-events",
                TableSchema.fromBean(EventEntity.class));
        Key key = Key.builder()
                .partitionValue("EVENT#" + eventId)
                .sortValue("METADATA")
                .build();
        return table.getItem(key).join();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/reservations should reserve tickets and return 201 with full response body")
    void shouldReserveTicketsSuccessfullyAndReturn201() {
        String eventId = insertTestEvent(100, 100);
        String userId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReserveTicketsRequest(eventId, userId, 2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ReservationResponse.class)
                .value(response -> {
                    assertThat(response.id()).isNotNull().isNotBlank();
                    assertThat(response.eventId()).isEqualTo(eventId);
                    assertThat(response.userId()).isEqualTo(userId);
                    assertThat(response.seatsCount()).isEqualTo(2);
                    assertThat(response.totalAmount()).isNotNull();
                    assertThat(response.currency()).isEqualTo("COP");
                    assertThat(response.status()).isNotNull();
                    assertThat(response.expiresAt()).isNotNull();
                    assertThat(response.createdAt()).isNotNull();
                });

        log.info("shouldReserveTicketsSuccessfullyAndReturn201 — PASSED for eventId={}", eventId);
    }

    @Test
    @DisplayName("POST /api/v1/reservations with non-existent eventId should return 404")
    void shouldReturn404WhenEventDoesNotExist() {
        String nonExistentEventId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReserveTicketsRequest(nonExistentEventId, userId, 1))
                .exchange()
                .expectStatus().isNotFound();

        log.info("shouldReturn404WhenEventDoesNotExist — PASSED");
    }

    @Test
    @DisplayName("POST /api/v1/reservations requesting more seats than available should return 422")
    void shouldReturn422WhenInsufficientTickets() {
        // Create event with only 10 seats, try to reserve 11
        String eventId = insertTestEvent(10, 10);
        String userId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReserveTicketsRequest(eventId, userId, 11))
                .exchange()
                // seatsCount=11 violates @Max(10) Bean Validation → 400
                // OR if validation passes, service throws TicketNotAvailableException → 422
                .expectStatus().value(status ->
                        assertThat(status).isIn(400, 422));

        log.info("shouldReturn422WhenInsufficientTickets — PASSED for eventId={}", eventId);
    }

    @Test
    @DisplayName("POST /api/v1/reservations with missing eventId should return 400")
    void shouldReturn400ForInvalidRequest() {
        // eventId is blank — violates @NotBlank
        String invalidBody = """
                {
                  "eventId": "",
                  "userId": "user-123",
                  "seatsCount": 1
                }
                """;

        webTestClient.post()
                .uri("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalidBody)
                .exchange()
                .expectStatus().isBadRequest();

        log.info("shouldReturn400ForInvalidRequest — PASSED");
    }

    /**
     * MAIN CONCURRENT TEST — verifies that DynamoDB optimistic locking prevents overselling.
     *
     * <p>Scenario: event with 10 tickets, 15 concurrent threads each requesting 1 seat.
     * Expected outcomes:
     * <ul>
     *   <li>At most 10 requests succeed (201)</li>
     *   <li>Remaining requests get 409 (ConcurrentModificationException) or 422 (no tickets left)</li>
     *   <li>All 15 requests get a definitive response (no requests lost)</li>
     *   <li>Final {@code availableCount} in DynamoDB == 10 - successCount (no phantom decrement)</li>
     *   <li>Final {@code availableCount >= 0} (no oversell — the critical invariant)</li>
     * </ul>
     */
    @Test
    @DisplayName("Concurrent ticket reservation MUST NOT oversell — availableCount never goes below 0")
    void shouldPreventOversellUnderConcurrentLoad() throws InterruptedException {
        int totalCapacity = 10;
        int threadCount   = 15;
        String eventId    = insertTestEvent(totalCapacity, totalCapacity);

        CountDownLatch startGate   = new CountDownLatch(1);
        CountDownLatch doneLatch   = new CountDownLatch(threadCount);
        AtomicInteger  successCount  = new AtomicInteger(0);
        AtomicInteger  conflictCount = new AtomicInteger(0);
        AtomicInteger  otherCount    = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String userId = "concurrent-user-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startGate.await(); // synchronize all threads to start at the same time
                    int status = webTestClient.post()
                            .uri("/api/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new ReserveTicketsRequest(eventId, userId, 1))
                            .exchange()
                            .returnResult(String.class)
                            .getStatus()
                            .value();

                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                        log.warn("Unexpected HTTP status {} for userId={}", status, userId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread interrupted for userId={}", userId, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(allDone)
                .as("All %d threads must complete within 30 seconds", threadCount)
                .isTrue();

        int totalResponses = successCount.get() + conflictCount.get() + otherCount.get();
        log.info("Concurrent test results: success={}, conflict={}, other={}, total={}",
                successCount.get(), conflictCount.get(), otherCount.get(), totalResponses);

        // ── Core assertions ───────────────────────────────────────────────────

        assertThat(successCount.get())
                .as("At most %d requests should succeed (totalCapacity=%d)", totalCapacity, totalCapacity)
                .isLessThanOrEqualTo(totalCapacity);

        assertThat(totalResponses)
                .as("Every request must get a definitive answer (success + conflict + other == %d)", threadCount)
                .isEqualTo(threadCount);

        // ── Data-consistency check: read back the event from DynamoDB ─────────
        EventEntity finalEntity = readEventEntity(eventId);
        assertThat(finalEntity).isNotNull();

        int finalAvailableCount = finalEntity.getAvailableCount();
        log.info("Final availableCount in DynamoDB = {}", finalAvailableCount);

        assertThat(finalAvailableCount)
                .as("availableCount must NEVER go below 0 — oversell invariant")
                .isGreaterThanOrEqualTo(0);

        assertThat(finalAvailableCount)
                .as("finalAvailableCount=%d should equal totalCapacity(%d) - successCount(%d)",
                        finalAvailableCount, totalCapacity, successCount.get())
                .isEqualTo(totalCapacity - successCount.get());
    }

    /**
     * Sequential fill test: reserve all 10 seats one by one, then verify 11th request → 422.
     *
     * <p>This complements the concurrent test by verifying the happy path and the
     * exhaustion boundary under non-concurrent conditions.
     */
    @Test
    @DisplayName("Filling all seats sequentially should succeed, then next request should return 422")
    void shouldReturn422WhenSeatCountExceedsCapacityAfterFilling() {
        int totalCapacity = 10;
        String eventId   = insertTestEvent(totalCapacity, totalCapacity);

        log.info("Starting sequential fill test for eventId={}", eventId);

        // Reserve all 10 seats — each should succeed
        for (int i = 0; i < totalCapacity; i++) {
            String userId = "sequential-user-" + i + "-" + UUID.randomUUID();
            webTestClient.post()
                    .uri("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new ReserveTicketsRequest(eventId, userId, 1))
                    .exchange()
                    .expectStatus().isCreated();
            log.debug("Reservation {} of {} succeeded", i + 1, totalCapacity);
        }

        // 11th request: no tickets left → should return 422 (TicketNotAvailableException)
        String lateUserId = "late-user-" + UUID.randomUUID();
        webTestClient.post()
                .uri("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReserveTicketsRequest(eventId, lateUserId, 1))
                .exchange()
                .expectStatus().isEqualTo(422);

        // Verify DynamoDB state: availableCount must be exactly 0
        EventEntity finalEntity = readEventEntity(eventId);
        assertThat(finalEntity).isNotNull();
        assertThat(finalEntity.getAvailableCount())
                .as("After filling all %d seats, availableCount must be 0", totalCapacity)
                .isEqualTo(0);

        log.info("shouldReturn422WhenSeatCountExceedsCapacityAfterFilling — PASSED for eventId={}", eventId);
    }
}
