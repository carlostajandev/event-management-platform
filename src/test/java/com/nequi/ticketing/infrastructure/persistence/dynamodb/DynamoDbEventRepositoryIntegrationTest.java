package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.model.Money;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbEventRepositoryIntegrationTest {

    // Far in the future so that events created by other tests never fall inside the listing window used here.
    private static final Instant NOW = Instant.parse("2090-01-01T00:00:00Z");

    private static DynamoDbEventRepository events;

    @BeforeAll
    static void setUp() {
        var dynamoDb = LocalDynamoDb.client();
        events = new DynamoDbEventRepository(dynamoDb, new TransactionWriter(dynamoDb), LocalDynamoDb.PROPERTIES);
    }

    @Test
    void storesTheEventWithItsInventory() {
        Event event = event(Duration.ofDays(10));

        StepVerifier.create(events.create(event, Inventory.initial(event.id(), 300), key(event, "k-1")))
                .expectNext(event)
                .verifyComplete();

        assertThat(events.findById(event.id()).block()).isEqualTo(event);
        assertThat(events.findInventory(event.id()).block()).isEqualTo(Inventory.initial(event.id(), 300));
    }

    @Test
    void unknownEventsAreEmpty() {
        StepVerifier.create(events.findById(EventId.random())).verifyComplete();
        StepVerifier.create(events.findInventory(EventId.random())).verifyComplete();
    }

    @Test
    void reusedIdempotencyKeyCreatesNothing() {
        Event first = event(Duration.ofDays(10));
        events.create(first, Inventory.initial(first.id(), 300), key(first, "same")).block();
        Event second = event(Duration.ofDays(11));

        StepVerifier.create(events.create(second, Inventory.initial(second.id(), 300), key(first, "same")))
                .expectError(DuplicateRequestException.class)
                .verify();

        StepVerifier.create(events.findById(second.id())).verifyComplete();
    }

    @Test
    void listsUpcomingEventsByDate() {
        Event later = event(Duration.ofDays(400));
        Event sooner = event(Duration.ofDays(399));
        events.create(later, Inventory.initial(later.id(), 300), key(later, "l")).block();
        events.create(sooner, Inventory.initial(sooner.id(), 300), key(sooner, "s")).block();

        StepVerifier.create(events.findUpcoming(NOW.plus(Duration.ofDays(398)), 10).map(Event::id))
                .expectNext(sooner.id(), later.id())
                .verifyComplete();
        StepVerifier.create(events.findUpcoming(NOW.plus(Duration.ofDays(399)), 10).map(Event::id))
                .expectNext(later.id())
                .verifyComplete();
    }

    private static Event event(Duration fromNow) {
        return Event.create(EventId.random(), "Theatre night", "Teatro Colon", NOW.plus(fromNow).truncatedTo(ChronoUnit.MILLIS),
                300, Money.of("80000", "COP"), NOW);
    }

    private static IdempotencyRecord key(Event event, String key) {
        return new IdempotencyRecord(new IdempotencyKey("admin", key), "hash", event.id().value(), NOW);
    }
}
