package com.nequi.ticketing.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Creates DynamoDB tables on application startup if they don't exist.
 * Uses the synchronous client to avoid event loop issues in @PostConstruct.
 */
@Component
public class DynamoDbTableInitializer {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    private final DynamoDbClient dynamoDbClient;
    private final AwsProperties awsProperties;

    public DynamoDbTableInitializer(DynamoDbClient dynamoDbClient,
                                    AwsProperties awsProperties) {
        this.dynamoDbClient = dynamoDbClient;
        this.awsProperties = awsProperties;
    }

    @PostConstruct
    public void initializeTables() {
        log.info("Initializing DynamoDB tables...");
        createEventsTable();
        createTicketsTable();
        createOrdersTable();
        createIdempotencyTable();
        createAuditTable();
        log.info("DynamoDB tables initialization complete.");
    }

    private void createEventsTable() {
        createTableIfNotExists(CreateTableRequest.builder()
                .tableName(awsProperties.dynamodb().tables().events())
                .attributeDefinitions(attr("eventId", ScalarAttributeType.S))
                .keySchema(key("eventId", KeyType.HASH))
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    private void createTicketsTable() {
        createTableIfNotExists(CreateTableRequest.builder()
                .tableName(awsProperties.dynamodb().tables().tickets())
                .attributeDefinitions(
                        attr("ticketId", ScalarAttributeType.S),
                        attr("eventId",  ScalarAttributeType.S),
                        attr("status",   ScalarAttributeType.S))
                .keySchema(key("ticketId", KeyType.HASH))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("eventId-status-index")
                        .keySchema(
                                key("eventId", KeyType.HASH),
                                key("status",  KeyType.RANGE))
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.ALL).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    private void createOrdersTable() {
        createTableIfNotExists(CreateTableRequest.builder()
                .tableName(awsProperties.dynamodb().tables().orders())
                .attributeDefinitions(
                        attr("orderId", ScalarAttributeType.S),
                        attr("userId",  ScalarAttributeType.S))
                .keySchema(key("orderId", KeyType.HASH))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("userId-index")
                        .keySchema(key("userId", KeyType.HASH))
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.ALL).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    private void createIdempotencyTable() {
        createTableIfNotExists(CreateTableRequest.builder()
                .tableName(awsProperties.dynamodb().tables().idempotency())
                .attributeDefinitions(attr("idempotencyKey", ScalarAttributeType.S))
                .keySchema(key("idempotencyKey", KeyType.HASH))
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    private void createAuditTable() {
        createTableIfNotExists(CreateTableRequest.builder()
                .tableName(awsProperties.dynamodb().tables().audit())
                .attributeDefinitions(
                        attr("entityId",  ScalarAttributeType.S),
                        attr("timestamp", ScalarAttributeType.S))
                .keySchema(
                        key("entityId",  KeyType.HASH),
                        key("timestamp", KeyType.RANGE))
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
    }

    private void createTableIfNotExists(CreateTableRequest request) {
        try {
            dynamoDbClient.createTable(request);
            log.info("Table created: {}", request.tableName());
        } catch (ResourceInUseException e) {
            log.debug("Table already exists: {}", request.tableName());
        } catch (Exception e) {
            log.error("Failed to create table {}: {}", request.tableName(), e.getMessage());
        }
    }

    private AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder()
                .attributeName(name).attributeType(type).build();
    }

    private KeySchemaElement key(String name, KeyType keyType) {
        return KeySchemaElement.builder()
                .attributeName(name).keyType(keyType).build();
    }
}