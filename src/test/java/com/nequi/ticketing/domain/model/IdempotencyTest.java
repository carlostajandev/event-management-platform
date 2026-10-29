package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyTest {

    @Test
    void keysAreScopedPerCaller() {
        assertThat(new IdempotencyKey("user-1", "abc")).isNotEqualTo(new IdempotencyKey("user-2", "abc"));
    }

    @Test
    void rejectsInvalidKeys() {
        assertThatThrownBy(() -> new IdempotencyKey(" ", "abc")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdempotencyKey(null, "abc")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdempotencyKey("user-1", "")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdempotencyKey("user-1", "k".repeat(129))).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void aRecordMatchesOnlyTheSameRequest() {
        IdempotencyRecord record = new IdempotencyRecord(new IdempotencyKey("user-1", "abc"), "hash-1", "order-1", Instant.EPOCH);

        assertThat(record.matches("hash-1")).isTrue();
        assertThat(record.matches("hash-2")).isFalse();
    }
}
