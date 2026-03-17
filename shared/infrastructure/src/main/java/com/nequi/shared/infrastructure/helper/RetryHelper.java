package com.nequi.shared.infrastructure.helper;

import com.nequi.shared.domain.exception.ConcurrentModificationException;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Reusable retry strategies for reactive pipelines.
 *
 * <p>Rule: never configure retry logic inline — always delegate to this helper
 * so the strategy is consistent and testable.
 */
public final class RetryHelper {

    private RetryHelper() {}

    /**
     * Retry strategy for DynamoDB conditional-write conflicts (optimistic locking).
     * Retries up to 3 times with 100ms exponential backoff, only on
     * {@link ConcurrentModificationException}.
     */
    public static Retry dynamoDbConditionalWriteRetry() {
        return Retry.backoff(3, Duration.ofMillis(100))
                .filter(ex -> ex instanceof ConcurrentModificationException)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
