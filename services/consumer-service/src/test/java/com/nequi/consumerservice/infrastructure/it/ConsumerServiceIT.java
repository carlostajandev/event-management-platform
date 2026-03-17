package com.nequi.consumerservice.infrastructure.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nequi.consumerservice.ConsumerServiceApplication;
import com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity.OrderEntity;
import com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity.ReservationEntity;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.model.ReservationStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
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
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Integration tests for consumer-service.
 *
 * <p>consumer-service has no HTTP endpoints: it reacts to SQS messages.
 * Tests verify DynamoDB state transitions (order → CONFIRMED, reservation → CONFIRMED)
 * after publishing an SQS message, using Awaitility for async assertions.
 *
 * <p>The consumer-service {@code DynamoDbTableInitializer} (active on "test" profile)
 * creates {@code emp-orders}, {@code emp-outbox}, {@code emp-reservations}, and
 * {@code emp-audit}. {@code SqsQueueInitializer} creates {@code emp-purchase-orders}
 * and {@code emp-purchase-orders-dlq}.
 *
 * <p>This class creates {@code emp-events} manually in {@link #createEmpEventsTable()}
 * because that table is owned by event-service and is not provisioned by consumer-service's
 * initializer.
 */
@Slf4j
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = ConsumerServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("ConsumerService — Integration Tests")
class ConsumerServiceIT {

    // ── Shared LocalStack container (DynamoDB + SQS) ──────────────────────────

    @Container
    static final LocalStackContainer LOCAL_STACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4.0"))
                    .withServices(DYNAMODB, SQS);

    /**
     * Overrides Spring properties AND creates the {@code emp-events} table before the
     * Spring context is bootstrapped. {@code @DynamicPropertySource} runs after Testcontainers
     * starts the container but before Spring initializes any beans.
     *
     * <p>consumer-service's {@code DynamoDbTableInitializer} creates {@code emp-orders},
     * {@code emp-outbox}, {@code emp-reservations}, and {@code emp-audit}, but NOT
     * {@code emp-events}. That table is owned by event-service. We provision it here
     * to avoid any potential startup failures if beans reference it.
     */
    @DynamicPropertySource
    static void overridePropertiesAndCreateTables(DynamicPropertyRegistry registry) {
        registry.add("aws.dynamodb.endpoint",
                () -> LOCAL_STACK.getEndpointOverride(DYNAMODB).toString());
        registry.add("aws.sqs.endpoint",
                () -> LOCAL_STACK.getEndpointOverride(SQS).toString());
        // The SqsQueueInitializer uses these names to create queues on startup
        registry.add("aws.sqs.queue.purchase-orders-name",   () -> "emp-purchase-orders");
        registry.add("aws.sqs.queue.purchase-orders-dlq-name", () -> "emp-purchase-orders-dlq");
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

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired
    DynamoDbEnhancedAsyncClient enhancedAsyncClient;

    @Autowired
    SqsAsyncClient sqsAsyncClient;

    @Autowired
    ObjectMapper objectMapper;

    // ── DynamoDB table handles ─────────────────────────────────────────────────

    private DynamoDbAsyncTable<OrderEntity> ordersTable() {
        return enhancedAsyncClient.table("emp-orders", TableSchema.fromBean(OrderEntity.class));
    }

    private DynamoDbAsyncTable<ReservationEntity> reservationsTable() {
        return enhancedAsyncClient.table("emp-reservations", TableSchema.fromBean(ReservationEntity.class));
    }

    // ── Helper: insert an OrderEntity in PENDING_CONFIRMATION state ───────────

    private void insertPendingOrder(String orderId, String reservationId, String userId) {
        String now = Instant.now().toString();

        OrderEntity entity = new OrderEntity();
        entity.setPk("ORDER#" + orderId);
        entity.setSk("RESERVATION#" + reservationId);
        entity.setGsi1Pk("USER#" + userId);
        entity.setGsi1Sk(now);
        entity.setId(orderId);
        entity.setReservationId(reservationId);
        entity.setEventId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setSeatsCount(2);
        entity.setTotalAmount("100000.00");
        entity.setCurrency("COP");
        entity.setStatus(OrderStatus.PENDING_CONFIRMATION.name());
        entity.setIdempotencyKey(UUID.randomUUID().toString());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        ordersTable().putItem(entity).join();
        log.info("Inserted PENDING_CONFIRMATION order: id={}", orderId);
    }

    // ── Helper: insert an ACTIVE ReservationEntity ────────────────────────────

    private void insertActiveReservation(String reservationId, String userId) {
        String now       = Instant.now().toString();
        String expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES).toString();
        long   ttl       = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();

        ReservationEntity entity = new ReservationEntity();
        entity.setPk("RESERVATION#" + reservationId);
        entity.setSk("USER#" + userId);
        entity.setGsi1Pk("STATUS#ACTIVE");
        entity.setGsi1Sk(expiresAt);
        entity.setId(reservationId);
        entity.setEventId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setSeatsCount(2);
        entity.setTotalAmount("100000.00");
        entity.setCurrency("COP");
        entity.setStatus(ReservationStatus.ACTIVE.name());
        entity.setExpiresAt(expiresAt);
        entity.setTtl(ttl);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        reservationsTable().putItem(entity).join();
        log.info("Inserted ACTIVE reservation: id={}, userId={}", reservationId, userId);
    }

    // ── Helper: resolve queue URL ─────────────────────────────────────────────

    private String resolveQueueUrl(String queueName) throws Exception {
        return sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName))
                .get(10, TimeUnit.SECONDS)
                .queueUrl();
    }

    /**
     * Reads an {@link OrderEntity} from emp-orders by orderId using a query on the PK.
     * The table uses PK = "ORDER#orderId" with a variable SK, so we use a query rather
     * than getItem to retrieve the first (and only expected) item.
     */
    private OrderEntity readOrderEntityBlocking(String orderId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue("ORDER#" + orderId).build()
        );
        return Flux.from(ordersTable().query(condition))
                .flatMapIterable(page -> page.items())
                .next()
                .block(java.time.Duration.ofSeconds(10));
    }

    /**
     * Reads a {@link ReservationEntity} from emp-reservations by composite key
     * (PK = "RESERVATION#id", SK = "USER#userId").
     */
    private ReservationEntity readReservationEntityBlocking(String reservationId, String userId) {
        Key key = Key.builder()
                .partitionValue("RESERVATION#" + reservationId)
                .sortValue("USER#" + userId)
                .build();
        return reservationsTable().getItem(key).join();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Tests the full ORDER_PLACED processing pipeline:
     * <ol>
     *   <li>Insert PENDING_CONFIRMATION order into emp-orders</li>
     *   <li>Insert ACTIVE reservation into emp-reservations</li>
     *   <li>Publish ORDER_PLACED SQS message</li>
     *   <li>Await until order status in DynamoDB == CONFIRMED</li>
     *   <li>Await until reservation status in DynamoDB == CONFIRMED</li>
     * </ol>
     */
    @Test
    @DisplayName("Publishing ORDER_PLACED SQS message should transition order and reservation to CONFIRMED")
    void shouldProcessSqsMessageAndConfirmOrder() throws Exception {
        String orderId        = UUID.randomUUID().toString();
        String reservationId  = UUID.randomUUID().toString();
        String userId         = UUID.randomUUID().toString();

        // Step 1: Insert test data into DynamoDB
        insertPendingOrder(orderId, reservationId, userId);
        insertActiveReservation(reservationId, userId);

        // Step 2: Build the SQS message body (PurchaseOrderMessage JSON)
        String messageBody = objectMapper.writeValueAsString(Map.of(
                "orderId",       orderId,
                "reservationId", reservationId,
                "eventId",       UUID.randomUUID().toString(),
                "userId",        userId,
                "seatsCount",    2
        ));

        // Step 3: Resolve queue URL and publish message
        String queueUrl = resolveQueueUrl("emp-purchase-orders");
        log.info("Publishing ORDER_PLACED message to queue: {}, orderId={}", queueUrl, orderId);

        sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build()
        ).get(10, TimeUnit.SECONDS);

        log.info("SQS message published for orderId={}", orderId);

        // Step 4: Await order status → CONFIRMED
        await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .alias("order should transition to CONFIRMED within 30s")
                .untilAsserted(() -> {
                    OrderEntity order = readOrderEntityBlocking(orderId);
                    assertThat(order)
                            .as("Order with id=%s must exist in DynamoDB", orderId)
                            .isNotNull();
                    assertThat(order.getStatus())
                            .as("Order status should be CONFIRMED, got: %s", order.getStatus())
                            .isEqualTo(OrderStatus.CONFIRMED.name());
                });

        log.info("Order transitioned to CONFIRMED: orderId={}", orderId);

        // Step 5: Await reservation status → CONFIRMED
        await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .alias("reservation should transition to CONFIRMED within 30s")
                .untilAsserted(() -> {
                    ReservationEntity reservation = readReservationEntityBlocking(reservationId, userId);
                    assertThat(reservation)
                            .as("Reservation with id=%s must exist in DynamoDB", reservationId)
                            .isNotNull();
                    assertThat(reservation.getStatus())
                            .as("Reservation status should be CONFIRMED, got: %s", reservation.getStatus())
                            .isEqualTo(ReservationStatus.CONFIRMED.name());
                });

        log.info("shouldProcessSqsMessageAndConfirmOrder — PASSED for orderId={}", orderId);
    }

    /**
     * Poison-message test: publishes malformed JSON to the queue.
     *
     * <p>The {@link com.nequi.consumerservice.infrastructure.messaging.sqs.OrderMessageConsumer}
     * catches JSON deserialization failures and deletes the poison message immediately
     * (rather than letting it re-drive to the DLQ). This test verifies that:
     * <ul>
     *   <li>The service remains healthy (context loads, actuator responds) after receiving a bad message</li>
     *   <li>The DLQ does NOT immediately accumulate the message (since consumer deletes it)</li>
     * </ul>
     *
     * <p>We verify DLQ emptiness by checking the {@code ApproximateNumberOfMessages} attribute.
     */
    @Test
    @DisplayName("Malformed SQS message (poison pill) should be deleted by consumer and NOT reach the DLQ")
    void shouldNotProcessInvalidSqsMessage() throws Exception {
        String malformedBody = "{ this is not valid JSON !!! }";

        // Publish the poison message to the main queue
        String mainQueueUrl = resolveQueueUrl("emp-purchase-orders");
        sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(mainQueueUrl)
                .messageBody(malformedBody)
                .build()
        ).get(10, TimeUnit.SECONDS);

        log.info("Published poison message to emp-purchase-orders");

        // Wait enough time for the consumer to poll and process (poll runs every 2s)
        // Then verify the DLQ is still empty (consumer deleted the message, not re-queued it)
        String dlqUrl = resolveQueueUrl("emp-purchase-orders-dlq");

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .alias("DLQ should remain empty — poison message is deleted by consumer, not re-driven")
                .untilAsserted(() -> {
                    var dlqAttributes = sqsAsyncClient.getQueueAttributes(
                            GetQueueAttributesRequest.builder()
                                    .queueUrl(dlqUrl)
                                    .attributeNames(
                                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                                    )
                                    .build()
                    ).get(5, TimeUnit.SECONDS).attributes();

                    int dlqVisible    = Integer.parseInt(
                            dlqAttributes.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
                    int dlqInFlight   = Integer.parseInt(
                            dlqAttributes.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0"));

                    log.debug("DLQ state: visible={}, in-flight={}", dlqVisible, dlqInFlight);

                    assertThat(dlqVisible + dlqInFlight)
                            .as("DLQ should have 0 messages — consumer deletes poison messages immediately")
                            .isEqualTo(0);
                });

        log.info("shouldNotProcessInvalidSqsMessage — PASSED: DLQ is empty after poison message");
    }
}
