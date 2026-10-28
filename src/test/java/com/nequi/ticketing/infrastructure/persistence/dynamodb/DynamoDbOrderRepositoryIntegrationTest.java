package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.exception.InsufficientTicketsException;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.model.InventoryMove;
import com.nequi.ticketing.domain.model.Money;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.model.UserId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nequi.ticketing.domain.model.TicketState.AVAILABLE;
import static com.nequi.ticketing.domain.model.TicketState.PENDING_CONFIRMATION;
import static com.nequi.ticketing.domain.model.TicketState.RESERVED;
import static com.nequi.ticketing.domain.model.TicketState.SOLD;
import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbOrderRepositoryIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final UserId USER = new UserId("user-1");

    private static DynamoDbAsyncClient dynamoDb;
    private static DynamoDbEventRepository events;
    private static DynamoDbOrderRepository orders;
    private static DynamoDbIdempotencyRepository idempotency;

    @BeforeAll
    static void setUp() {
        dynamoDb = LocalDynamoDb.client();
        TransactionWriter writer = new TransactionWriter(dynamoDb);
        events = new DynamoDbEventRepository(dynamoDb, writer, LocalDynamoDb.PROPERTIES);
        orders = new DynamoDbOrderRepository(dynamoDb, writer, LocalDynamoDb.PROPERTIES);
        idempotency = new DynamoDbIdempotencyRepository(dynamoDb, LocalDynamoDb.PROPERTIES);
    }

    @Test
    void placingAnOrderWritesEverythingInOneTransaction() {
        Event event = newEvent(10);
        StateTransition placed = place(event, 3);
        IdempotencyRecord key = key(placed.order());

        StepVerifier.create(orders.create(placed, key)).expectNext(placed.order()).verifyComplete();

        assertThat(inventory(event)).isEqualTo(new Inventory(event.id(), 10, 7, 3, 0, 0, 0));
        assertThat(orders.findById(placed.order().id()).block()).isEqualTo(placed.order());
        assertThat(idempotency.find(key.key()).block()).isEqualTo(key);
        assertThat(audit(placed.order())).singleElement()
                .satisfies(item -> assertThat(item.get("toState").s()).isEqualTo("RESERVED"));
        assertThat(outbox(placed.order())).containsEntry("pendingKey", AttributeValue.fromS("PENDING"));
    }

    @Test
    void cannotReserveMoreThanAvailableAndNothingIsWritten() {
        Event event = newEvent(2);
        StateTransition placed = place(event, 3);

        StepVerifier.create(orders.create(placed, key(placed.order())))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(InsufficientTicketsException.class)
                        .hasMessageContaining("2 tickets available, 3 requested"))
                .verify();

        assertThat(inventory(event)).isEqualTo(Inventory.initial(event.id(), 2));
        assertThat(orders.findById(placed.order().id()).block()).isNull();
        assertThat(audit(placed.order())).isEmpty();
    }

    @Test
    void unknownEventIsNotFound() {
        Event ghost = Event.create(EventId.random(), "Ghost", "Nowhere", NOW.plus(Duration.ofDays(1)), 5,
                Money.of("1000", "COP"), NOW);
        StateTransition placed = place(ghost, 1);

        StepVerifier.create(orders.create(placed, key(placed.order())))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void reusedIdempotencyKeyIsRejectedWithoutTouchingInventory() {
        Event event = newEvent(10);
        StateTransition first = place(event, 1);
        IdempotencyRecord key = key(first.order());
        orders.create(first, key).block();

        StateTransition retry = place(event, 1);
        StepVerifier.create(orders.create(retry, key))
                .expectError(DuplicateRequestException.class)
                .verify();

        assertThat(inventory(event).reserved()).isEqualTo(1);
    }

    @Test
    void duplicateReplayWinsOverSoldOut() {
        Event event = newEvent(1);
        StateTransition first = place(event, 1);
        IdempotencyRecord key = key(first.order());
        orders.create(first, key).block();

        StepVerifier.create(orders.create(place(event, 1), key))
                .expectError(DuplicateRequestException.class)
                .verify();
    }

    @Test
    void walksTheWholeLifecycleAndKeepsTheHoldIndexInSync() {
        Event event = newEvent(5);
        StateTransition placed = place(event, 2);
        orders.create(placed, key(placed.order())).block();

        StateTransition accepted = placed.order().startConfirmation(NOW);
        StepVerifier.create(orders.apply(accepted)).expectNextCount(1).verifyComplete();
        assertThat(rawOrder(placed.order()).get("holdStatus").s()).isEqualTo("PENDING_CONFIRMATION");

        StateTransition sold = accepted.order().confirmSale(NOW);
        StepVerifier.create(orders.apply(sold)).expectNextCount(1).verifyComplete();

        assertThat(orders.findById(placed.order().id()).block().status()).isEqualTo(SOLD);
        assertThat(rawOrder(placed.order())).doesNotContainKey("holdStatus");
        assertThat(inventory(event)).isEqualTo(new Inventory(event.id(), 5, 3, 0, 0, 2, 0));
        assertThat(audit(placed.order())).extracting(item -> item.get("toState").s())
                .containsExactly("RESERVED", "PENDING_CONFIRMATION", "SOLD");
    }

    @Test
    void aDuplicateMessageCannotApplyTheSameTransitionTwice() {
        Event event = newEvent(5);
        StateTransition placed = place(event, 1);
        orders.create(placed, key(placed.order())).block();
        StateTransition accepted = placed.order().startConfirmation(NOW);
        orders.apply(accepted).block();

        StepVerifier.create(orders.apply(accepted))
                .expectError(OrderStateChangedException.class)
                .verify();

        assertThat(inventory(event)).isEqualTo(new Inventory(event.id(), 5, 4, 0, 1, 0, 0));
    }

    @Test
    void aLateMessageCannotReviveAReleasedOrder() {
        Event event = newEvent(5);
        StateTransition placed = place(event, 2);
        orders.create(placed, key(placed.order())).block();
        Instant afterDeadline = placed.order().expiresAt();
        orders.apply(placed.order().release(afterDeadline)).block();

        StepVerifier.create(orders.apply(placed.order().startConfirmation(NOW)))
                .expectError(OrderStateChangedException.class)
                .verify();

        assertThat(inventory(event)).isEqualTo(Inventory.initial(event.id(), 5));
        assertThat(orders.findById(placed.order().id()).block().status()).isEqualTo(AVAILABLE);
    }

    @Test
    void theDeadlineIsCheckedByDynamoDbNotOnlyInMemory() {
        Event event = newEvent(5);
        StateTransition placed = place(event, 1);
        orders.create(placed, key(placed.order())).block();
        Order reserved = placed.order();
        // A transition stamped after the deadline must be refused by the write itself.
        StateTransition stale = new StateTransition(
                new Order(reserved.id(), reserved.eventId(), reserved.userId(), 1, reserved.totalAmount(),
                        PENDING_CONFIRMATION, reserved.createdAt(), reserved.expiresAt(), reserved.expiresAt()),
                RESERVED, PENDING_CONFIRMATION,
                new InventoryMove(RESERVED, PENDING_CONFIRMATION, 1),
                reserved.expiresAt());

        StepVerifier.create(orders.apply(stale)).expectError(OrderStateChangedException.class).verify();
    }

    @Test
    void findsOnlyExpiredHolds() {
        Event event = newEvent(10);
        StateTransition expired = place(event, 1);
        StateTransition pendingExpired = place(event, 1);
        StateTransition sold = place(event, 1);
        for (StateTransition placed : List.of(expired, pendingExpired, sold)) {
            orders.create(placed, key(placed.order())).block();
        }
        orders.apply(pendingExpired.order().startConfirmation(NOW)).block();
        StateTransition accepted = sold.order().startConfirmation(NOW);
        orders.apply(accepted).block();
        orders.apply(accepted.order().confirmSale(NOW)).block();

        List<OrderId> found = orders.findExpiredHolds(NOW.plus(Order.HOLD_DURATION), 1000)
                .map(Order::id).collectList().block();
        List<OrderId> notYet = orders.findExpiredHolds(NOW.plus(Order.HOLD_DURATION).minusMillis(1), 1000)
                .map(Order::id).collectList().block();

        assertThat(found).contains(expired.order().id(), pendingExpired.order().id()).doesNotContain(sold.order().id());
        assertThat(notYet).doesNotContain(expired.order().id(), pendingExpired.order().id());
    }

    @Test
    void complimentaryTicketsAreNotEnqueued() {
        Event event = newEvent(10);
        StateTransition issued = Order.complimentary(OrderId.random(), event, USER, 4, NOW);

        orders.create(issued, key(issued.order())).block();

        assertThat(inventory(event)).isEqualTo(new Inventory(event.id(), 10, 6, 0, 0, 0, 4));
        assertThat(outbox(issued.order())).isEmpty();
        assertThat(rawOrder(issued.order())).doesNotContainKeys("holdStatus", "expiresAt");
    }

    @Test
    void neverOversellsUnderConcurrentPurchases() {
        int capacity = 50;
        int buyers = 200;
        Event event = newEvent(capacity);
        AtomicInteger reserved = new AtomicInteger();
        Map<String, AtomicInteger> rejected = new ConcurrentHashMap<>();

        Flux.range(0, buyers)
                .flatMap(i -> {
                    StateTransition placed = place(event, 1);
                    return orders.create(placed, key(placed.order()))
                            .doOnNext(order -> reserved.incrementAndGet())
                            .onErrorResume(error -> {
                                rejected.computeIfAbsent(error.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                                return Mono.empty();
                            });
                }, buyers)
                .blockLast(Duration.ofMinutes(2));

        Inventory inventory = inventory(event);
        assertThat(reserved.get()).isEqualTo(capacity);
        assertThat(rejected).containsOnlyKeys("InsufficientTicketsException");
        assertThat(rejected.get("InsufficientTicketsException").get()).isEqualTo(buyers - capacity);
        assertThat(inventory).isEqualTo(new Inventory(event.id(), capacity, 0, capacity, 0, 0, 0));
    }

    @Test
    void consumerAndExpiryRacingOnOneOrderHaveExactlyOneWinner() {
        for (int round = 0; round < 20; round++) {
            Event event = newEvent(1);
            StateTransition placed = place(event, 1);
            orders.create(placed, key(placed.order())).block();
            Order order = placed.order();
            // The consumer read the order just before the deadline; the expiry job runs at the deadline.
            StateTransition accept = order.startConfirmation(order.expiresAt().minusMillis(1));
            StateTransition release = order.release(order.expiresAt());

            List<Boolean> outcomes = Flux.just(accept, release)
                    .flatMap(transition -> orders.apply(transition).thenReturn(true)
                            .onErrorResume(OrderStateChangedException.class, e -> Mono.just(false)), 2)
                    .collectList().block();

            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            Inventory inventory = inventory(event);
            assertThat(inventory.available() + inventory.pendingConfirmation()).isEqualTo(1);
            assertThat(inventory.reserved()).isZero();
        }
    }

    private static Event newEvent(int capacity) {
        Event event = Event.create(EventId.random(), "Rock Fest", "Movistar Arena", NOW.plus(Duration.ofDays(30)),
                capacity, Money.of("150000", "COP"), NOW);
        IdempotencyRecord key = new IdempotencyRecord(new IdempotencyKey("admin", event.id().value()), "hash",
                event.id().value(), NOW);
        events.create(event, Inventory.initial(event.id(), capacity), key).block();
        return event;
    }

    private static StateTransition place(Event event, int quantity) {
        return Order.place(OrderId.random(), event, USER, quantity, NOW);
    }

    private static IdempotencyRecord key(Order order) {
        return new IdempotencyRecord(new IdempotencyKey(USER.value(), "key-" + order.id().value()), "hash",
                order.id().value(), NOW);
    }

    private static Inventory inventory(Event event) {
        return events.findInventory(event.id()).block();
    }

    private static Map<String, AttributeValue> rawOrder(Order order) {
        return dynamoDb.getItem(r -> r.tableName("ticketing-orders").key(OrderItems.key(order.id())).consistentRead(true))
                .join().item();
    }

    private static List<Map<String, AttributeValue>> audit(Order order) {
        return dynamoDb.query(r -> r.tableName("ticketing-audit")
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("ORDER#" + order.id().value()))))
                .join().items();
    }

    private static Map<String, AttributeValue> outbox(Order order) {
        return dynamoDb.getItem(r -> r.tableName("ticketing-outbox")
                        .key(Map.of("PK", AttributeValue.fromS("OUTBOX#OrderPlaced#" + order.id().value()))))
                .join().item();
    }
}
