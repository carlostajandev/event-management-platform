package com.nequi.ticketing.infrastructure.persistence.dynamodb.repository;

import com.nequi.ticketing.application.port.out.AuditRepository;
import com.nequi.ticketing.domain.model.AuditEntry;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB implementation of AuditRepository.
 * Uses low-level putItem to avoid creating a full @DynamoDbBean entity.
 */
@Repository
public class AuditDynamoDbRepository implements AuditRepository {

    private final DynamoDbEnhancedAsyncClient enhancedClient;
    private final software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient dynamoDbClient;
    private final String tableName;

    public AuditDynamoDbRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient dynamoDbClient,
            AwsProperties awsProperties) {
        this.enhancedClient = enhancedClient;
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = awsProperties.dynamodb().tables().audit();
    }

    @Override
    public Mono<Void> save(AuditEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("entityId",      str(entry.entityId()));
        item.put("timestamp",     str(entry.timestamp().toString()));
        item.put("entityType",    str(entry.entityType()));
        item.put("action",        str(entry.action()));
        item.put("newStatus",     str(entry.newStatus()));

        if (entry.userId() != null)
            item.put("userId", str(entry.userId()));
        if (entry.correlationId() != null)
            item.put("correlationId", str(entry.correlationId()));
        if (entry.previousStatus() != null)
            item.put("previousStatus", str(entry.previousStatus()));
        if (entry.metadata() != null)
            item.put("metadata", str(entry.metadata()));

        var request = software.amazon.awssdk.services.dynamodb.model.PutItemRequest
                .builder()
                .tableName(tableName)
                .item(item)
                .build();

        return Mono.fromCompletionStage(() -> dynamoDbClient.putItem(request)).then();
    }

    private AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
