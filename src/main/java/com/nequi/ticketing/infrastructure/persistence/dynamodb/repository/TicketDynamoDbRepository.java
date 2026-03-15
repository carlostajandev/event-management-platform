package com.nequi.ticketing.infrastructure.persistence.dynamodb.repository;

import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.TicketId;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.TicketDynamoDbEntity;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.mapper.TicketDynamoDbMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.Map;

@Repository
public class TicketDynamoDbRepository implements TicketRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketDynamoDbRepository.class);
    private static final String GSI_NAME = "eventId-status-index";

    private final DynamoDbAsyncTable<TicketDynamoDbEntity> table;
    private final TicketDynamoDbMapper mapper;

    public TicketDynamoDbRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            AwsProperties awsProperties,
            TicketDynamoDbMapper mapper) {
        this.table = enhancedClient.table(
                awsProperties.dynamodb().tables().tickets(),
                TableSchema.fromBean(TicketDynamoDbEntity.class));
        this.mapper = mapper;
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Ticket> save(Ticket ticket) {
        TicketDynamoDbEntity entity = mapper.toEntity(ticket);
        return Mono.fromCompletionStage(() -> table.putItem(entity))
                .thenReturn(ticket);
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Ticket> findById(TicketId ticketId) {
        Key key = Key.builder().partitionValue(ticketId.value()).build();
        return Mono.fromCompletionStage(() -> table.getItem(key))
                .map(mapper::toDomain);
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Flux<Ticket> findByEventIdAndStatus(EventId eventId, TicketStatus status) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder()
                        .partitionValue(eventId.value())
                        .sortValue(status.name())
                        .build());

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .build();

        return Flux.from(table.index(GSI_NAME).query(request)
                        .flatMapIterable(page -> page.items()))
                .map(mapper::toDomain);
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Flux<Ticket> findAvailableByEventId(EventId eventId, int limit) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder()
                        .partitionValue(eventId.value())
                        .sortValue(TicketStatus.AVAILABLE.name())
                        .build());

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .limit(limit)
                .build();

        return Flux.from(table.index(GSI_NAME).query(request)
                        .flatMapIterable(page -> page.items()))
                .map(mapper::toDomain);
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Long> countByEventIdAndStatus(EventId eventId, TicketStatus status) {
        return findByEventIdAndStatus(eventId, status).count();
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Flux<Ticket> findExpiredReservations(Instant before) {
        // Scan for RESERVED tickets where expiresAt < before
        // In production this would use a GSI on expiresAt for efficiency
        return Flux.from(table.scan().items())
                .map(mapper::toDomain)
                .filter(ticket -> ticket.status() == TicketStatus.RESERVED
                        && ticket.expiresAt() != null
                        && ticket.expiresAt().isBefore(before));
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Ticket> update(Ticket ticket) {
        TicketDynamoDbEntity entity = mapper.toEntity(ticket);

        Expression condition = Expression.builder()
                .expression("#version = :expectedVersion")
                .expressionNames(Map.of("#version", "version"))
                .expressionValues(Map.of(
                        ":expectedVersion",
                        AttributeValue.builder()
                                .n(String.valueOf(ticket.version() - 1))
                                .build()))
                .build();

        PutItemEnhancedRequest<TicketDynamoDbEntity> request = PutItemEnhancedRequest
                .<TicketDynamoDbEntity>builder(TicketDynamoDbEntity.class)
                .item(entity)
                .conditionExpression(condition)
                .build();

        return Mono.fromCompletionStage(() -> table.putItem(request))
                .thenReturn(ticket)
                .onErrorMap(ConditionalCheckFailedException.class, ex ->
                        new RuntimeException("Concurrent modification on ticket: "
                                + ticket.ticketId().value()));
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Flux<Ticket> saveAll(Flux<Ticket> tickets) {
        return tickets.flatMap(this::save);
    }
}