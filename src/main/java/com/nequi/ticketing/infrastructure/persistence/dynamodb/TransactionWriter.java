package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs a TransactWriteItems and classifies cancellations.
 *
 * <ul>
 *   <li>A failed condition is a business outcome (sold out, state changed, duplicate key): it is mapped by
 *       the caller and never retried.</li>
 *   <li>A conflict with a concurrent transaction or throttling is transient: the same request is sent again
 *       with jittered backoff. Conditions are evaluated by DynamoDB on every attempt, so a retry never acts on
 *       stale values read by the client.</li>
 * </ul>
 */
@Component
class TransactionWriter {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final Set<String> TRANSIENT = Set.of("TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded");

    private final DynamoDbAsyncClient dynamoDb;
    private final RetryBackoffSpec retry;

    @Autowired
    TransactionWriter(DynamoDbAsyncClient dynamoDb) {
        this(dynamoDb, Retry.backoff(5, Duration.ofMillis(10)).maxBackoff(Duration.ofMillis(200)).jitter(0.75));
    }

    TransactionWriter(DynamoDbAsyncClient dynamoDb, RetryBackoffSpec retry) {
        this.dynamoDb = dynamoDb;
        this.retry = retry
                .filter(TransientConflict.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> new TransactionContentionException(signal.failure().getCause()));
    }

    /**
     * @param onConditionFailure maps the per-item cancellation reasons (same order as {@code items}) to the
     *                           business exception to signal
     */
    Mono<Void> write(List<TransactWriteItem> items, Function<List<CancellationReason>, ? extends RuntimeException> onConditionFailure) {
        return Mono.fromFuture(() -> dynamoDb.transactWriteItems(request -> request.transactItems(items)))
                .onErrorMap(TransactionCanceledException.class, e -> classify(e, onConditionFailure))
                .retryWhen(retry)
                .then();
    }

    private static Throwable classify(TransactionCanceledException e,
                                      Function<List<CancellationReason>, ? extends RuntimeException> onConditionFailure) {
        List<CancellationReason> reasons = e.cancellationReasons();
        if (reasons.stream().anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()))) {
            return onConditionFailure.apply(reasons);
        }
        if (reasons.stream().anyMatch(reason -> TRANSIENT.contains(reason.code()))) {
            return new TransientConflict(e);
        }
        return e;
    }

    static boolean conditionFailed(List<CancellationReason> reasons, int index) {
        return index < reasons.size() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    private static final class TransientConflict extends RuntimeException {

        private TransientConflict(Throwable cause) {
            super(cause);
        }
    }
}
