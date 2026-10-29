package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static com.nequi.ticketing.domain.model.Fixtures.EVENT_ID;
import static com.nequi.ticketing.domain.model.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Instant NEXT_MONTH = NOW.plus(Duration.ofDays(30));
    private static final Money PRICE = Money.of("150000", "COP");

    @Test
    void createsAnUpcomingEvent() {
        Event event = Fixtures.event(500);

        assertThat(event.capacity()).isEqualTo(500);
        assertThat(event.createdAt()).isEqualTo(NOW);
        assertThat(event.hasHappened(NOW)).isFalse();
        assertThat(event.hasHappened(NEXT_MONTH)).isTrue();
    }

    @Test
    void rejectsADateThatIsNotInTheFuture() {
        assertThatThrownBy(() -> Event.create(EVENT_ID, "Rock Fest", "Arena", NOW, 10, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("future");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Event.MAX_CAPACITY + 1})
    void rejectsCapacityOutOfRange(int capacity) {
        assertThatThrownBy(() -> Event.create(EVENT_ID, "Rock Fest", "Arena", NEXT_MONTH, capacity, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsInvalidText() {
        assertThatThrownBy(() -> Event.create(EVENT_ID, " ", "Arena", NEXT_MONTH, 10, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(EVENT_ID, "Rock Fest", "a".repeat(201), NEXT_MONTH, 10, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(EVENT_ID, null, "Arena", NEXT_MONTH, 10, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> Event.create(null, "Rock Fest", "Arena", NEXT_MONTH, 10, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(EVENT_ID, "Rock Fest", "Arena", null, 10, PRICE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(EVENT_ID, "Rock Fest", "Arena", NEXT_MONTH, 10, null, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Event(EVENT_ID, "Rock Fest", "Arena", NEXT_MONTH, 10, PRICE, null))
                .isInstanceOf(DomainValidationException.class);
    }
}
