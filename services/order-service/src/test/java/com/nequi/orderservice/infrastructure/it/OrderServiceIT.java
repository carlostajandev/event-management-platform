package com.nequi.orderservice.infrastructure.it;

import com.nequi.orderservice.OrderServiceApplication;
import com.nequi.orderservice.application.dto.CreateOrderRequest;
import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.orderservice.infrastructure.persistence.dynamodb.entity.ReservationEntity;
import com.nequi.shared.domain.model.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

/**
 * Integration tests for order-service HTTP endpoints.
 *
 * <p>The order-service {@code DynamoDbTableInitializer} (active on "test" profile)
 * creates {@code emp-orders}, {@code emp-outbox}, {@code emp-idempotency-keys}, and
 * {@code emp-audit} automatically. This class creates {@code emp-reservations} manually
 * in {@link #createEmpReservationsTable()} because that table is owned by reservation-service
 * and is not provisioned by order-service's initializer.
 *
 * <p>Tests cover order creation (with idempotency), duplicate key deduplication,
 * reservation-not-found error handling, missing-header validation, and order status retrieval.
 */
@Slf4j
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = OrderServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("OrderService — Integration Tests")
class OrderServiceIT {

    // ── Shared LocalStack container ───────────────────────────────────────────

    @Container
    static final LocalStackContainer LOCAL_STACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4.0"))
                    .withServices(DYNAMODB);

    /**
     * Overrides Spring properties AND creates the {@code emp-reservations} table before
     * the Spring context is bootstrapped. {@code @DynamicPropertySource} runs after
     * Testcontainers has started the container but before Spring initializes any beans.
     *
     * <p>order-service's own {@code DynamoDbTableInitializer} creates {@code emp-orders},
     * {@code emp-outbox}, {@code emp-idempotency-keys}, and {@code emp-audit}, but NOT
     * {@code emp-reservations}. We must create it here so it exists when the
     * {@code DynamoDbReservationRepository} bean is initialised.
     */
    @DynamicPropertySource
    static void overridePropertiesAndCreateTables(DynamicPropertyRegistry registry) {
        registry.add("aws.dynamodb.endpoint",
                () -> LOCAL_STACK.getEndpointOverride(DYNAMODB).toString());
        registry.add("aws.region", LOCAL_STACK::getRegion);
        registry.add("aws.accessKeyId",  LOCAL_STACK::getAccessKey);
        registry.add("aws.secretAccessKey", LOCAL_STACK::getSecretKey);

        // Create emp-reservations table before Spring context starts
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
                        .tableName("emp-reservations")
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                AttributeDefinition.builder()
                                        .attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder()
                                        .attributeName("SK").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder()
                                        .attributeName("GSI1PK").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder()
                                        .attributeName("GSI1SK").attributeType(ScalarAttributeType.S).build()
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
                                                        .attributeName("GSI1PK").keyType(KeyType.HASH).build(),
                                                KeySchemaElement.builder()
                                                        .attributeName("GSI1SK").keyType(KeyType.RANGE).build()
                                        )
                                        .projection(Projection.builder()
                                                .projectionType(ProjectionType.ALL).build())
                                        .build()
                        )
                        .build());
                log.info("emp-reservations table created in @DynamicPropertySource (before Spring context)");
            } catch (ResourceInUseException e) {
                log.info("emp-reservations table already exists — skipping creation");
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
     * Enhanced async client from the order-service Spring context.
     * Used to insert {@link ReservationEntity} test fixtures directly.
     */
    @Autowired
    DynamoDbEnhancedAsyncClient enhancedAsyncClient;

    // ── Helper: insert a ACTIVE ReservationEntity into emp-reservations ───────

    private String insertActiveReservation(String userId) {
        String reservationId = UUID.randomUUID().toString();
        String eventId       = UUID.randomUUID().toString();
        String now           = Instant.now().toString();
        String expiresAt     = Instant.now().plus(15, ChronoUnit.MINUTES).toString();
        long   ttl           = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();

        var table = enhancedAsyncClient.table("emp-reservations",
                TableSchema.fromBean(ReservationEntity.class));

        ReservationEntity entity = new ReservationEntity();
        entity.setPk("RESERVATION#" + reservationId);
        entity.setSk("USER#" + userId);
        entity.setGsi1Pk("STATUS#ACTIVE");
        entity.setGsi1Sk(expiresAt);
        entity.setId(reservationId);
        entity.setEventId(eventId);
        entity.setUserId(userId);
        entity.setSeatsCount(2);
        entity.setTotalAmount("100000.00");
        entity.setCurrency("COP");
        entity.setStatus("ACTIVE");
        entity.setExpiresAt(expiresAt);
        entity.setTtl(ttl);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        table.putItem(entity).join();
        log.info("Inserted ACTIVE reservation: id={}, userId={}", reservationId, userId);
        return reservationId;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/orders should create order and return 201 with PENDING_CONFIRMATION status")
    void shouldCreateOrderSuccessfullyAndReturn201() {
        String userId        = UUID.randomUUID().toString();
        String reservationId = insertActiveReservation(userId);
        String idempotencyKey = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .bodyValue(new CreateOrderRequest(reservationId, userId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .value(response -> {
                    assertThat(response.id()).isNotNull().isNotBlank();
                    assertThat(response.reservationId()).isEqualTo(reservationId);
                    assertThat(response.userId()).isEqualTo(userId);
                    assertThat(response.seatsCount()).isEqualTo(2);
                    assertThat(response.totalAmount()).isNotNull();
                    assertThat(response.currency()).isEqualTo("COP");
                    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                    assertThat(response.createdAt()).isNotNull();
                    assertThat(response.updatedAt()).isNotNull();
                });

        log.info("shouldCreateOrderSuccessfullyAndReturn201 — PASSED for reservationId={}", reservationId);
    }

    @Test
    @DisplayName("Duplicate X-Idempotency-Key should return the same orderId on second request")
    void shouldReturnSameResponseForDuplicateIdempotencyKey() {
        String userId        = UUID.randomUUID().toString();
        String reservationId = insertActiveReservation(userId);
        String idempotencyKey = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest(reservationId, userId);

        // First request — creates the order
        OrderResponse first = webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(first).isNotNull();
        String originalOrderId = first.id();
        assertThat(originalOrderId).isNotBlank();

        // Second request with same idempotency key — must return the cached response
        OrderResponse second = webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(second).isNotNull();
        assertThat(second.id())
                .as("Duplicate idempotency key must return the same orderId")
                .isEqualTo(originalOrderId);

        log.info("shouldReturnSameResponseForDuplicateIdempotencyKey — PASSED, orderId={}", originalOrderId);
    }

    @Test
    @DisplayName("POST /api/v1/orders with non-existent reservationId should return 404")
    void shouldReturn404WhenReservationNotFound() {
        String nonExistentReservationId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();

        webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", UUID.randomUUID().toString())
                .bodyValue(new CreateOrderRequest(nonExistentReservationId, userId))
                .exchange()
                .expectStatus().isNotFound();

        log.info("shouldReturn404WhenReservationNotFound — PASSED");
    }

    @Test
    @DisplayName("POST /api/v1/orders without X-Idempotency-Key header should return 400")
    void shouldReturn400WhenIdempotencyKeyHeaderIsMissing() {
        String userId        = UUID.randomUUID().toString();
        String reservationId = UUID.randomUUID().toString();

        // No X-Idempotency-Key header
        webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateOrderRequest(reservationId, userId))
                .exchange()
                .expectStatus().isBadRequest();

        log.info("shouldReturn400WhenIdempotencyKeyHeaderIsMissing — PASSED");
    }

    @Test
    @DisplayName("GET /api/v1/orders/{orderId} should return 200 with PENDING_CONFIRMATION status after creation")
    void shouldGetOrderStatus() {
        String userId        = UUID.randomUUID().toString();
        String reservationId = insertActiveReservation(userId);
        String idempotencyKey = UUID.randomUUID().toString();

        // Create the order
        OrderResponse created = webTestClient.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .bodyValue(new CreateOrderRequest(reservationId, userId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        String orderId = created.id();
        assertThat(orderId).isNotBlank();

        // Fetch by orderId
        webTestClient.get()
                .uri("/api/v1/orders/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .value(response -> {
                    assertThat(response.id()).isEqualTo(orderId);
                    assertThat(response.reservationId()).isEqualTo(reservationId);
                    assertThat(response.userId()).isEqualTo(userId);
                    assertThat(response.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                });

        log.info("shouldGetOrderStatus — PASSED for orderId={}", orderId);
    }
}
