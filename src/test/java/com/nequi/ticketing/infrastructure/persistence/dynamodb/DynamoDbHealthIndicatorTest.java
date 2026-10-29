package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.boot.health.contributor.Status;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamoDbHealthIndicatorTest {

    private static final TicketingProperties PROPERTIES = new TicketingProperties(
            new TicketingProperties.Aws("us-east-1", "", Duration.ofSeconds(2), Duration.ofMillis(500)),
            new TicketingProperties.Tables("events", "orders", "idempotency", "audit", "outbox"));

    private final DynamoDbAsyncClient dynamoDb = mock(DynamoDbAsyncClient.class);
    private final DynamoDbHealthIndicator indicator = new DynamoDbHealthIndicator(dynamoDb, PROPERTIES);

    @Test
    void upWhenAllTablesAreActive() {
        whenDescribeTable().thenReturn(CompletableFuture.completedFuture(withStatus(TableStatus.ACTIVE)));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsOnlyKeys("events", "orders", "idempotency", "audit", "outbox");
                })
                .verifyComplete();
    }

    @Test
    void downWhenAnyTableIsNotActive() {
        whenDescribeTable().thenReturn(
                CompletableFuture.completedFuture(withStatus(TableStatus.ACTIVE)),
                CompletableFuture.completedFuture(withStatus(TableStatus.CREATING)));

        StepVerifier.create(indicator.health())
                .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.DOWN))
                .verifyComplete();
    }

    @Test
    void downWhenATableIsMissing() {
        whenDescribeTable().thenReturn(CompletableFuture.failedFuture(
                ResourceNotFoundException.builder().message("Cannot do operations on a non-existent table").build()));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsKey("error");
                })
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    private OngoingStubbing<CompletableFuture<DescribeTableResponse>> whenDescribeTable() {
        return when(dynamoDb.describeTable(any(Consumer.class)));
    }

    private static DescribeTableResponse withStatus(TableStatus status) {
        return DescribeTableResponse.builder().table(table -> table.tableStatus(status)).build();
    }
}
