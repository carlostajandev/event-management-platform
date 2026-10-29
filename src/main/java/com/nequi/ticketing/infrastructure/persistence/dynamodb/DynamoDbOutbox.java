package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.string;

/** Read and acknowledge side of the transactional outbox. Messages are written by {@link DynamoDbOrderRepository}. */
@Component
public class DynamoDbOutbox {

    static final Duration PUBLISHED_RETENTION = Duration.ofHours(24);
    private static final String PENDING_INDEX = "pending-by-time";

    public record Message(String id, String type, String orderId, String eventId) {
    }

    private final DynamoDbAsyncClient dynamoDb;
    private final String table;

    DynamoDbOutbox(DynamoDbAsyncClient dynamoDb, TicketingProperties properties) {
        this.dynamoDb = dynamoDb;
        this.table = properties.tables().outbox();
    }

    /** Oldest unpublished messages first. */
    public Flux<Message> pending(int limit) {
        return Mono.fromFuture(() -> dynamoDb.query(request -> request
                        .tableName(table)
                        .indexName(PENDING_INDEX)
                        .keyConditionExpression("pendingKey = :pending")
                        .expressionAttributeValues(Map.of(":pending", s(OutboxItems.PENDING)))
                        .limit(limit)))
                .flatMapIterable(response -> response.items())
                .map(item -> new Message(string(item, PK), string(item, "messageType"), string(item, "orderId"),
                        string(item, "eventId")));
    }

    /**
     * Takes the message out of the pending index and lets TTL delete it later. Already acknowledged by another
     * relay is fine: delivery is at least once and the consumer is idempotent.
     */
    public Mono<Void> markPublished(Message message, Instant now) {
        return Mono.fromFuture(() -> dynamoDb.updateItem(request -> request
                        .tableName(table)
                        .key(Map.of(PK, s(message.id())))
                        .updateExpression("REMOVE pendingKey SET publishedAt = :now, #ttl = :ttl")
                        .conditionExpression("attribute_exists(pendingKey)")
                        .expressionAttributeNames(Map.of("#ttl", "ttl"))
                        .expressionAttributeValues(Map.of(
                                ":now", millis(now),
                                ":ttl", n(now.plus(PUBLISHED_RETENTION).getEpochSecond())))))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, alreadyPublished -> Mono.empty());
    }
}
