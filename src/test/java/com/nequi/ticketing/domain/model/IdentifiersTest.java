package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentifiersTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void rejectsInvalidValues(String value) {
        assertThatThrownBy(() -> new EventId(value)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderId(value)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new UserId(value)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void acceptsUpToSixtyFourCharacters() {
        assertThat(new UserId("u".repeat(64)).value()).hasSize(64);
    }

    @Test
    void randomIdsAreUnique() {
        assertThat(EventId.random()).isNotEqualTo(EventId.random());
        assertThat(OrderId.random()).isNotEqualTo(OrderId.random());
    }
}
