package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.port.IdempotencyRepository;
import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

@Repository
class DynamoDbIdempotencyRepository implements IdempotencyRepository {

    private final DynamoDbAsyncClient dynamoDb;
    private final String table;

    DynamoDbIdempotencyRepository(DynamoDbAsyncClient dynamoDb, TicketingProperties properties) {
        this.dynamoDb = dynamoDb;
        this.table = properties.tables().idempotency();
    }

    @Override
    public Mono<IdempotencyRecord> find(IdempotencyKey key) {
        return Mono.fromFuture(() -> dynamoDb.getItem(request -> request
                        .tableName(table)
                        .key(IdempotencyItems.key(key))
                        .consistentRead(true)))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> IdempotencyItems.fromItem(response.item()));
    }
}
