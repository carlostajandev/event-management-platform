package com.nequi.reservationservice.infrastructure.config;

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

import java.util.List;

/**
 * Creates DynamoDB tables required by the reservation-service on startup.
 *
 * <p>Only active on {@code local} and {@code test} profiles. In production,
 * tables are provisioned by Terraform (see {@code infrastructure/terraform/dynamodb.tf}).
 *
 * <p>Tables created:
 * <ol>
 *   <li>{@code emp-reservations} — PK/SK + GSI1 (status + expiresAt) + TTL on {@code ttl}</li>
 *   <li>{@code emp-audit}        — PK/SK + TTL on {@code ttl}</li>
 * </ol>
 *
 * <p>{@link ResourceInUseException} is silently swallowed — tables already existing
 * is normal in integration tests or restarts.
 */
@Slf4j
@Component
@Profile({"local", "test"})
public class DynamoDbTableInitializer {

    private final DynamoDbClient dynamoDbClient;
    private final String         reservationsTableName;
    private final String         auditTableName;

    public DynamoDbTableInitializer(
            DynamoDbClient dynamoDbClient,
            @Value("${aws.dynamodb.table.reservations:emp-reservations}") String reservationsTableName,
            @Value("${aws.dynamodb.table.audit:emp-audit}") String auditTableName) {
        this.dynamoDbClient       = dynamoDbClient;
        this.reservationsTableName = reservationsTableName;
        this.auditTableName        = auditTableName;
    }

    @PostConstruct
    public void initializeTables() {
        createReservationsTable();
        createAuditTable();
    }

    // ── emp-reservations ──────────────────────────────────────────────────────

    private void createReservationsTable() {
        log.info("Initializing DynamoDB table: {}", reservationsTableName);
        try {
            CreateTableRequest createTableRequest = CreateTableRequest.builder()
                    .tableName(reservationsTableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    // ── Attribute definitions ──────────────────────────────────
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
                    // ── Primary key schema ─────────────────────────────────────
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    // ── GSI1: query expired active reservations (O(results), not O(table)) ──
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("GSI1")
                                    .keySchema(
                                            KeySchemaElement.builder()
                                                    .attributeName("GSI1PK").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder()
                                                    .attributeName("GSI1SK").keyType(KeyType.RANGE).build()
                                    )
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build()
                    )
                    .build();

            dynamoDbClient.createTable(createTableRequest);
            log.info("DynamoDB table created: {}", reservationsTableName);

            // Enable TTL on the 'ttl' attribute — DynamoDB auto-deletes expired reservations
            enableTtl(reservationsTableName, "ttl");

        } catch (ResourceInUseException ex) {
            log.info("DynamoDB table already exists, skipping: {}", reservationsTableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}, error={}", reservationsTableName, ex.getMessage(), ex);
            throw new IllegalStateException("Cannot initialize DynamoDB table: " + reservationsTableName, ex);
        }
    }

    // ── emp-audit ─────────────────────────────────────────────────────────────

    private void createAuditTable() {
        log.info("Initializing DynamoDB table: {}", auditTableName);
        try {
            CreateTableRequest createTableRequest = CreateTableRequest.builder()
                    .tableName(auditTableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder()
                                    .attributeName("SK").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(
                            KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build()
                    )
                    .build();

            dynamoDbClient.createTable(createTableRequest);
            log.info("DynamoDB table created: {}", auditTableName);

            // Enable TTL on 'ttl' attribute — audit entries auto-deleted after 90 days
            enableTtl(auditTableName, "ttl");

        } catch (ResourceInUseException ex) {
            log.info("DynamoDB table already exists, skipping: {}", auditTableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}, error={}", auditTableName, ex.getMessage(), ex);
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
            // TTL enable failure is non-fatal in local dev (LocalStack may not support it fully)
            log.warn("Could not enable TTL on table={}: {}", tableName, ex.getMessage());
        }
    }
}
