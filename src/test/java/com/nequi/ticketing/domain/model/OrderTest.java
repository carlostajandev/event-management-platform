package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.HoldExpiredException;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static com.nequi.ticketing.domain.model.Fixtures.EVENT_ID;
import static com.nequi.ticketing.domain.model.Fixtures.NOW;
import static com.nequi.ticketing.domain.model.Fixtures.USER;
import static com.nequi.ticketing.domain.model.Fixtures.event;
import static com.nequi.ticketing.domain.model.Fixtures.reservedOrder;
import static com.nequi.ticketing.domain.model.TicketState.AVAILABLE;
import static com.nequi.ticketing.domain.model.TicketState.COMPLIMENTARY;
import static com.nequi.ticketing.domain.model.TicketState.PENDING_CONFIRMATION;
import static com.nequi.ticketing.domain.model.TicketState.RESERVED;
import static com.nequi.ticketing.domain.model.TicketState.SOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final OrderId ORDER_ID = new OrderId("order-1");
    private static final Instant DEADLINE = NOW.plus(Order.HOLD_DURATION);
    private static final Instant BEFORE_DEADLINE = DEADLINE.minusMillis(1);

    @Nested
    class Place {

        @Test
        void holdsTicketsForTenMinutes() {
            StateTransition transition = Order.place(ORDER_ID, event(100), USER, 2, NOW);
            Order order = transition.order();

            assertThat(order.status()).isEqualTo(RESERVED);
            assertThat(order.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
            assertThat(order.totalAmount()).isEqualTo(Money.of("300000", "COP"));
            assertThat(order.eventId()).isEqualTo(EVENT_ID);
            assertThat(transition.from()).isEqualTo(AVAILABLE);
            assertThat(transition.to()).isEqualTo(RESERVED);
            assertThat(transition.move()).isEqualTo(new InventoryMove(AVAILABLE, RESERVED, 2));
            assertThat(transition.at()).isEqualTo(NOW);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Order.MAX_TICKETS_PER_ORDER + 1})
        void limitsTicketsPerOrder(int quantity) {
            assertThatThrownBy(() -> Order.place(ORDER_ID, event(100), USER, quantity, NOW))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void rejectsEventsThatAlreadyHappened() {
            Event event = event(100);

            assertThatThrownBy(() -> Order.place(ORDER_ID, event, USER, 1, event.date()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("already taken place");
        }
    }

    @Nested
    class Complimentary {

        @Test
        void isFinalFreeAndHasNoDeadline() {
            StateTransition transition = Order.complimentary(ORDER_ID, event(100), USER, 10, NOW);

            assertThat(transition.order().status()).isEqualTo(COMPLIMENTARY);
            assertThat(transition.order().expiresAt()).isNull();
            assertThat(transition.order().totalAmount().amount()).isZero();
            assertThat(transition.move()).isEqualTo(new InventoryMove(AVAILABLE, COMPLIMENTARY, 10));
            assertThat(transition.order().isExpired(NOW.plus(Duration.ofDays(365)))).isFalse();
        }

        @Test
        void rejectsInvalidQuantityAndPastEvents() {
            Event event = event(100);

            assertThatThrownBy(() -> Order.complimentary(ORDER_ID, event, USER, 0, NOW))
                    .isInstanceOf(DomainValidationException.class);
            assertThatThrownBy(() -> Order.complimentary(ORDER_ID, event, USER, 1, event.date()))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Confirmation {

        @Test
        void consumerMovesAReservedOrderToPendingConfirmation() {
            StateTransition transition = reservedOrder(2).startConfirmation(BEFORE_DEADLINE);

            assertThat(transition.order().status()).isEqualTo(PENDING_CONFIRMATION);
            assertThat(transition.order().updatedAt()).isEqualTo(BEFORE_DEADLINE);
            assertThat(transition.order().expiresAt()).isEqualTo(DEADLINE);
            assertThat(transition.move()).isEqualTo(new InventoryMove(RESERVED, PENDING_CONFIRMATION, 2));
        }

        @Test
        void paymentMovesAPendingOrderToSold() {
            Order pending = reservedOrder(1).startConfirmation(NOW).order();

            StateTransition transition = pending.confirmSale(BEFORE_DEADLINE);

            assertThat(transition.order().status()).isEqualTo(SOLD);
            assertThat(transition.move()).isEqualTo(new InventoryMove(PENDING_CONFIRMATION, SOLD, 1));
        }

        @Test
        void theDeadlineItselfIsAlreadyExpired() {
            Order reserved = reservedOrder(1);
            Order pending = reserved.startConfirmation(NOW).order();

            assertThatThrownBy(() -> reserved.startConfirmation(DEADLINE)).isInstanceOf(HoldExpiredException.class);
            assertThatThrownBy(() -> pending.confirmSale(DEADLINE))
                    .isInstanceOf(HoldExpiredException.class)
                    .hasMessageContaining("order-1");
        }

        @Test
        void requiresTheExpectedState() {
            Order reserved = reservedOrder(1);
            Order sold = reserved.startConfirmation(NOW).order().confirmSale(NOW).order();

            assertThatThrownBy(() -> reserved.confirmSale(NOW)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> sold.startConfirmation(NOW)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> sold.confirmSale(NOW)).isInstanceOf(IllegalTransitionException.class);
        }
    }

    @Nested
    class Release {

        @Test
        void returnsExpiredHoldsToAvailable() {
            Order reserved = reservedOrder(3);
            Order pending = reserved.startConfirmation(NOW).order();

            StateTransition fromReserved = reserved.release(DEADLINE);
            StateTransition fromPending = pending.release(DEADLINE);

            assertThat(fromReserved.order().status()).isEqualTo(AVAILABLE);
            assertThat(fromReserved.move()).isEqualTo(new InventoryMove(RESERVED, AVAILABLE, 3));
            assertThat(fromPending.move()).isEqualTo(new InventoryMove(PENDING_CONFIRMATION, AVAILABLE, 3));
        }

        @Test
        void neverReleasesAnActiveHold() {
            assertThatThrownBy(() -> reservedOrder(1).release(BEFORE_DEADLINE))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("held until");
        }

        @Test
        void neverReleasesFinalOrders() {
            Order sold = reservedOrder(1).startConfirmation(NOW).order().confirmSale(NOW).order();
            Order complimentary = Order.complimentary(ORDER_ID, event(10), USER, 1, NOW).order();
            Order released = reservedOrder(1).release(DEADLINE).order();

            assertThatThrownBy(() -> sold.release(DEADLINE)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> complimentary.release(DEADLINE)).isInstanceOf(IllegalTransitionException.class);
            assertThatThrownBy(() -> released.release(DEADLINE)).isInstanceOf(IllegalTransitionException.class);
        }
    }

    @Test
    void fullLifecycleKeepsTheInventoryInvariant() {
        Inventory inventory = Inventory.initial(EVENT_ID, 100);
        StateTransition placed = Order.place(ORDER_ID, event(100), USER, 4, NOW);
        StateTransition accepted = placed.order().startConfirmation(NOW);
        StateTransition sold = accepted.order().confirmSale(NOW);

        Inventory after = inventory.apply(placed.move()).apply(accepted.move()).apply(sold.move());

        assertThat(after).isEqualTo(new Inventory(EVENT_ID, 100, 96, 0, 0, 4, 0));
    }

    @Test
    void rejectsIncompleteOrders() {
        Money total = Money.of("1", "COP");
        assertThatThrownBy(() -> new Order(null, EVENT_ID, USER, 1, total, RESERVED, NOW, DEADLINE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Order(ORDER_ID, EVENT_ID, USER, 0, total, RESERVED, NOW, DEADLINE, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Order(ORDER_ID, EVENT_ID, USER, 1, total, RESERVED, NOW, null, NOW))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("deadline");
    }
}
