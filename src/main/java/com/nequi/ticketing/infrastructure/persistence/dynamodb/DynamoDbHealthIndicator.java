package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reports UP only when every table the application uses exists and is ACTIVE.
 * Part of the readiness group, so a process is not routed traffic before its tables are reachable.
 */
@Component
class DynamoDbHealthIndicator extends AbstractReactiveHealthIndicator {

    private final DynamoDbAsyncClient dynamoDb;
    private final List<String> tables;

    DynamoDbHealthIndicator(DynamoDbAsyncClient dynamoDb, TicketingProperties properties) {
        super("DynamoDB health check failed");
        this.dynamoDb = dynamoDb;
        TicketingProperties.Tables names = properties.tables();
        this.tables = List.of(names.events(), names.orders(), names.idempotency(), names.audit(), names.outbox());
    }

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Flux.fromIterable(tables)
                .flatMap(this::tableStatus)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, TreeMap::new)
                .map(statuses -> {
                    boolean allActive = statuses.values().stream().allMatch(TableStatus.ACTIVE::equals);
                    return (allActive ? builder.up() : builder.down()).withDetails(statuses).build();
                });
    }

    private Mono<Map.Entry<String, TableStatus>> tableStatus(String table) {
        return Mono.fromFuture(() -> dynamoDb.describeTable(request -> request.tableName(table)))
                .map(response -> Map.entry(table, response.table().tableStatus()));
    }
}
