package com.nequi.eventservice.infrastructure.config;

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

import java.util.List;

/**
 * Creates the {@code emp-events} DynamoDB table on application startup.
 *
 * <p>Only active on {@code local} and {@code test} profiles. In production and staging
 * the table is provisioned by Terraform (see {@code infrastructure/terraform/dynamodb.tf}).
 *
 * <p>Table schema:
 * <pre>
 *   Table: emp-events   Billing: PAY_PER_REQUEST
 *   PK (S): PK          SK (S): SK
 *   GSI1:   GSI1PK (S)  — projection ALL — for findByStatus queries
 * </pre>
 */
@Slf4j
@Component
@Profile({"local", "test"})
public class DynamoDbTableInitializer {

    private final DynamoDbClient dynamoDbClient;
    private final String         tableName;

    public DynamoDbTableInitializer(
            DynamoDbClient dynamoDbClient,
            @Value("${aws.dynamodb.table.events:emp-events}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName      = tableName;
    }

    @PostConstruct
    public void initializeTable() {
        log.info("Initializing DynamoDB table: {}", tableName);
        try {
            CreateTableRequest createTableRequest = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    // ── Attribute definitions ──────────────────────────────────
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("PK")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("SK")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("GSI1PK")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    // ── Primary key schema ─────────────────────────────────────
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("PK")
                                    .keyType(KeyType.HASH)
                                    .build(),
                            KeySchemaElement.builder()
                                    .attributeName("SK")
                                    .keyType(KeyType.RANGE)
                                    .build()
                    )
                    // ── Global Secondary Index: list events by status ──────────
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("GSI1")
                                    .keySchema(
                                            KeySchemaElement.builder()
                                                    .attributeName("GSI1PK")
                                                    .keyType(KeyType.HASH)
                                                    .build()
                                    )
                                    .projection(
                                            Projection.builder()
                                                    .projectionType(ProjectionType.ALL)
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            dynamoDbClient.createTable(createTableRequest);
            log.info("DynamoDB table created successfully: {}", tableName);

        } catch (ResourceInUseException ex) {
            // Table already exists — safe to ignore (idempotent initialization)
            log.info("DynamoDB table already exists, skipping creation: {}", tableName);
        } catch (Exception ex) {
            log.error("Failed to initialize DynamoDB table: {}, error={}", tableName, ex.getMessage(), ex);
            throw new IllegalStateException("Cannot initialize DynamoDB table: " + tableName, ex);
        }
    }
}