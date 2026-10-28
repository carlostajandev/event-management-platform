package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.infrastructure.config.ApiProperties.RateLimit;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void allowsTheBurstThenRefillsAtTheSustainedRate() {
        TokenBucket bucket = new TokenBucket(2, 3, 0);

        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isFalse();

        assertThat(bucket.tryConsume(SECOND / 2)).isTrue();
        assertThat(bucket.tryConsume(SECOND / 2)).isFalse();
    }

    @Test
    void neverStoresMoreThanTheBurst() {
        TokenBucket bucket = new TokenBucket(100, 2, 0);

        assertThat(bucket.tryConsume(60 * SECOND)).isTrue();
        assertThat(bucket.tryConsume(60 * SECOND)).isTrue();
        assertThat(bucket.tryConsume(60 * SECOND)).isFalse();
    }

    @Test
    void limiterKeepsOneBucketPerKey() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(new RateLimit(1, 1), 10, now::get);

        assertThat(limiter.tryAcquire("ana")).isTrue();
        assertThat(limiter.tryAcquire("ana")).isFalse();
        assertThat(limiter.tryAcquire("luis")).isTrue();

        now.addAndGet(SECOND);
        assertThat(limiter.tryAcquire("ana")).isTrue();
    }

    @Test
    void disabledLimiterAllowsEverything() {
        RateLimiter limiter = new RateLimiter(new RateLimit(0, 1), 10, () -> 0);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("ana")).isTrue();
        }
    }
}
