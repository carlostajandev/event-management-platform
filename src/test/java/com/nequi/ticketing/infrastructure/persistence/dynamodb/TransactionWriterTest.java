package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionWriterTest {

    private final DynamoDbAsyncClient dynamoDb = mock(DynamoDbAsyncClient.class);
    private final TransactionWriter writer = new TransactionWriter(dynamoDb, Retry.backoff(2, Duration.ofMillis(1)));

    @Test
    @SuppressWarnings("unchecked")
    void retriesTransientConflictsAndSucceeds() {
        when(dynamoDb.transactWriteItems(any(Consumer.class)))
                .thenReturn(cancelled("None", "TransactionConflict"), cancelled("ThrottlingError"), success());

        StepVerifier.create(writer.write(List.of(), reasons -> new IllegalStateException())).verifyComplete();

        verify(dynamoDb, times(3)).transactWriteItems(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void givesUpWithAContentionErrorAfterTheLastRetry() {
        when(dynamoDb.transactWriteItems(any(Consumer.class))).thenReturn(cancelled("TransactionConflict"));

        StepVerifier.create(writer.write(List.of(), reasons -> new IllegalStateException()))
                .expectError(TransactionContentionException.class)
                .verify();

        verify(dynamoDb, times(3)).transactWriteItems(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverRetriesAFailedCondition() {
        when(dynamoDb.transactWriteItems(any(Consumer.class)))
                .thenReturn(cancelled("ConditionalCheckFailed", "TransactionConflict"));

        StepVerifier.create(writer.write(List.of(), reasons -> new SoldOut()))
                .expectError(SoldOut.class)
                .verify();

        verify(dynamoDb, times(1)).transactWriteItems(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesThroughOtherCancellations() {
        when(dynamoDb.transactWriteItems(any(Consumer.class))).thenReturn(cancelled("ValidationError"));

        StepVerifier.create(writer.write(List.of(), reasons -> new IllegalStateException()))
                .expectError(TransactionCanceledException.class)
                .verify();
    }

    private static CompletableFuture<TransactWriteItemsResponse> success() {
        return CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
    }

    private static CompletableFuture<TransactWriteItemsResponse> cancelled(String... codes) {
        List<CancellationReason> reasons = Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build()).toList();
        return CompletableFuture.failedFuture(TransactionCanceledException.builder().cancellationReasons(reasons).build());
    }

    private static final class SoldOut extends RuntimeException {
    }
}
