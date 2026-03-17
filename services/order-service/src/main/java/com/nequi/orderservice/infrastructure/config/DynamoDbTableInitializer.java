package com.nequi.orderservice.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
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
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Creates DynamoDB tables required by order-service on startup.
 *
 * <p>Only active on {@code local} and {@code test} profiles. In production,
 * tables are provisioned by Terraform.
 *
 * <p>Tables created:
 * <ol>
 *   <li>{@code emp-orders} — PK/SK + GSI1 (userId/createdAt)</li>
 *   <li>{@code emp-outbox} — PK/SK + GSI1 (published status) + TTL</li>
 *   <li>{@code emp-idempotency-keys} — PK/SK + TTL</li>
 *   <li>{@code emp-audit} — PK/SK + TTL</li>
 * </ol>
 */
@Slf4j
@Component
@Profile({"local", "test"})
public class DynamoDbTableInitializer {

    private final DynamoDbClient dynamoDbClient;
    private final String         ordersTableName;
    private final String         outboxTableName;
    private final String         idempotencyTableName;
    private final String         auditTableName;

    public DynamoDbTableInitializer(
            DynamoDbClient dynamoDbClient,
            @Value("${aws.dynamodb.table.orders:emp-orders}") String ordersTableName,
            @Value("${aws.dynamodb.table.outbox:emp-outbox}") String outboxTableName,
            @Value("${aws.dynamodb.table.idempotency:emp-idempotency-keys}") String idempotencyTableName,
            @Value("${aws.dynamodb.table.audit:emp-audit}") String auditTableName) {
        this.dynamoDbClient       = dynamoDbClient;
        this.ordersTableName      = ordersTableName;
        this.outboxTableName      = outboxTableName;
        this.idempotencyTableName = idempotencyTableName;
        this.auditTableName       = auditTableName;
    }

    @PostConstruct
    public void initializeTables() {
        createOrdersTable();
        createOutboxTable();
        createIdempotencyTable();
        createAuditTable();
    }

    // ── emp-orders ────────────────────────────────────────────────────────────

    private void createOrdersTable() {
        log.info("Initializing DynamoDB table: {}", ordersTableName);
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(ordersTableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("GSI1PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("GSI1SK").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("GSI1")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("GSI1PK").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("GSI1SK").keyType(KeyType.RANGE).build()
                                    )
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build()
                    )
                    .build());
            log.info("DynamoDB table created: {}", ordersTableName);
        } catch (ResourceInUseException ex) {
            log.info("DynamoDB table already exists, skipping: {}", ordersTableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}", ordersTableName, ex);
            throw new IllegalStateException("Cannot initialize DynamoDB table: " + ordersTableName, ex);
        }
    }

    // ── emp-outbox ────────────────────────────────────────────────────────────

    private void createOutboxTable() {
        log.info("Initializing DynamoDB table: {}", outboxTableName);
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(outboxTableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("GSI1PK").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("GSI1")
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("GSI1PK").keyType(KeyType.HASH).build()
                                    )
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build()
                    )
                    .build());
            log.info("DynamoDB table created: {}", outboxTableName);
            enableTtl(outboxTableName, "ttl");
        } catch (ResourceInUseException ex) {
            log.info("DynamoDB table already exists, skipping: {}", outboxTableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}", outboxTableName, ex);
            throw new IllegalStateException("Cannot initialize DynamoDB table: " + outboxTableName, ex);
        }
    }

    // ── emp-idempotency-keys ──────────────────────────────────────────────────

    private void createIdempotencyTable() {
        log.info("Initializing DynamoDB table: {}", idempotencyTableName);
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(idempotencyTableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    .build());
            log.info("DynamoDB table created: {}", idempotencyTableName);
            enableTtl(idempotencyTableName, "ttl");
        } catch (ResourceInUseException ex) {
            log.info("DynamoDB table already exists, skipping: {}", idempotencyTableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}", idempotencyTableName, ex);
            throw new IllegalStateException("Cannot initialize DynamoDB table: " + idempotencyTableName, ex);
        }
    }

    // ── emp-audit ─────────────────────────────────────────────────────────────

    private void createAuditTable() {
        log.info("Initializing DynamoDB table: {}", auditTableName);
        try {
            dynamoDbClient.createTable(CreateTableRequest.builder()
                    .tableName(auditTableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    .build());
            log.info("DynamoDB table created: {}", auditTableName);
            enableTtl(auditTableName, "ttl");
        } catch (ResourceInUseException ex) {
            log.info("DynamoDB table already exists, skipping: {}", auditTableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}", auditTableName, ex);
            throw new IllegalStateException("Cannot initialize DynamoDB table: " + auditTableName, ex);
        }
    }

    // ── TTL helper ────────────────────────────────────────────────────────────

    private void enableTtl(String tableName, String ttlAttribute) {
        try {
            dynamoDbClient.updateTimeToLive(
                    UpdateTimeToLiveRequest.builder()
                            .tableName(tableName)
                            .timeToLiveSpecification(
                                    TimeToLiveSpecification.builder()
                                            .enabled(true)
                                            .attributeName(ttlAttribute)
                                            .build()
                            )
                            .build()
            );
            log.debug("TTL enabled on table={}, attribute={}", tableName, ttlAttribute);
        } catch (Exception ex) {
            log.warn("Could not enable TTL on table={}: {}", tableName, ex.getMessage());
        }
    }
}
