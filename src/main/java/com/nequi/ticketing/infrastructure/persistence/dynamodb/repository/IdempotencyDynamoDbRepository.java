package com.nequi.ticketing.infrastructure.persistence.dynamodb.repository;

import com.nequi.ticketing.domain.repository.IdempotencyRepository;
import com.nequi.ticketing.domain.valueobject.IdempotencyKey;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.IdempotencyDynamoDbEntity;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;

@Repository
public class IdempotencyDynamoDbRepository implements IdempotencyRepository {

    private final DynamoDbAsyncTable<IdempotencyDynamoDbEntity> table;

    public IdempotencyDynamoDbRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            AwsProperties awsProperties) {
        this.table = enhancedClient.table(
                awsProperties.dynamodb().tables().idempotency(),
                TableSchema.fromBean(IdempotencyDynamoDbEntity.class));
    }

    @Override
    public Mono<Boolean> exists(IdempotencyKey key) {
        return findById(key).map(e -> true).defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> save(IdempotencyKey key, String responseJson, int ttlHours) {
        IdempotencyDynamoDbEntity entity = new IdempotencyDynamoDbEntity();
        entity.setIdempotencyKey(key.value());
        entity.setResponseJson(responseJson);
        entity.setCreatedAt(Instant.now().toString());
        entity.setExpiresAt(Instant.now().plusSeconds((long) ttlHours * 3600).getEpochSecond());

        PutItemEnhancedRequest<IdempotencyDynamoDbEntity> request =
                PutItemEnhancedRequest.builder(IdempotencyDynamoDbEntity.class)
                        .item(entity)
                        .conditionExpression(Expression.builder()
                                .expression("attribute_not_exists(idempotencyKey)")
                                .build())
                        .build();

        return Mono.fromCompletionStage(() -> table.putItem(request))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, ex ->
                        // Another concurrent request already saved — this is expected and fine
                        Mono.empty());
    }

    @Override
    public Mono<String> findResponse(IdempotencyKey key) {
        return findById(key).map(IdempotencyDynamoDbEntity::getResponseJson);
    }

    private Mono<IdempotencyDynamoDbEntity> findById(IdempotencyKey key) {
        Key tableKey = Key.builder().partitionValue(key.value()).build();
        return Mono.fromCompletionStage(() -> table.getItem(tableKey));
    }

    @Override
    public Mono<Void> updateResponse(IdempotencyKey key, String responseJson) {
        return findById(key)
                .flatMap(entity -> {
                    entity.setResponseJson(responseJson);
                    return Mono.fromCompletionStage(() -> table.putItem(entity)).then();
                });
    }
}
