package com.nequi.ticketing.application.order;

import com.nequi.ticketing.application.idempotency.IdempotentReplay;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.model.UserId;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdempotencyRepository;
import com.nequi.ticketing.domain.port.OrderRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.nequi.ticketing.application.Fixtures.CLOCK;
import static com.nequi.ticketing.application.Fixtures.KEY;
import static com.nequi.ticketing.application.Fixtures.NOW;
import static com.nequi.ticketing.application.Fixtures.USER;
import static com.nequi.ticketing.application.Fixtures.event;
import static com.nequi.ticketing.domain.model.TicketState.COMPLIMENTARY;
import static com.nequi.ticketing.domain.model.TicketState.PENDING_CONFIRMATION;
import static com.nequi.ticketing.domain.model.TicketState.RESERVED;
import static com.nequi.ticketing.domain.model.TicketState.SOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderUseCasesTest {

    private final EventRepository events = mock(EventRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final IdempotentReplay replay = new IdempotentReplay(idempotency);
    private final GetOrderUseCase getOrder = new GetOrderUseCase(orders);

    @Nested
    class Place {

        private final PlaceOrderUseCase placeOrder = new PlaceOrderUseCase(events, orders, replay, CLOCK);

        @Test
        void holdsTicketsAndStoresTheIdempotencyRecordWithTheOrder() {
            Event event = event();
            when(idempotency.find(KEY)).thenReturn(Mono.empty());
            when(events.findById(event.id())).thenReturn(Mono.just(event));
            when(orders.create(any(), any())).thenAnswer(call -> Mono.just(call.<StateTransition>getArgument(0).order()));

            StepVerifier.create(placeOrder.execute(command(event.id(), 2)))
                    .assertNext(order -> {
                        assertThat(order.status()).isEqualTo(RESERVED);
                        assertThat(order.expiresAt()).isEqualTo(NOW.plus(Order.HOLD_DURATION));
                    })
                    .verifyComplete();

            ArgumentCaptor<StateTransition> transition = ArgumentCaptor.forClass(StateTransition.class);
            ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(orders).create(transition.capture(), record.capture());
            assertThat(record.getValue().resourceId()).isEqualTo(transition.getValue().order().id().value());
            assertThat(record.getValue().requestHash()).isEqualTo("hash");
        }

        @Test
        void unknownEventIsNotFoundAndNothingIsWritten() {
            EventId unknown = new EventId("unknown");
            when(idempotency.find(KEY)).thenReturn(Mono.empty());
            when(events.findById(unknown)).thenReturn(Mono.empty());

            StepVerifier.create(placeOrder.execute(command(unknown, 1))).expectError(NotFoundException.class).verify();
            verify(orders, never()).create(any(), any());
        }

        @Test
        void aDoubleClickReturnsTheSameOrder() {
            Order existing = Order.place(new OrderId("order-1"), event(), USER, 1, NOW).order();
            when(idempotency.find(KEY)).thenReturn(Mono.just(new IdempotencyRecord(KEY, "hash", "order-1", NOW)));
            when(orders.findById(existing.id())).thenReturn(Mono.just(existing));

            StepVerifier.create(placeOrder.execute(command(existing.eventId(), 1))).expectNext(existing).verifyComplete();
            verify(events, never()).findById(any());
        }

        @Test
        void aReplayOfAMissingOrderIsNotFound() {
            when(idempotency.find(KEY)).thenReturn(Mono.just(new IdempotencyRecord(KEY, "hash", "gone", NOW)));
            when(orders.findById(new OrderId("gone"))).thenReturn(Mono.empty());

            StepVerifier.create(placeOrder.execute(command(new EventId("event-1"), 1)))
                    .expectError(NotFoundException.class)
                    .verify();
        }
    }

    @Test
    void issuesComplimentaryTickets() {
        Event event = event();
        when(idempotency.find(KEY)).thenReturn(Mono.empty());
        when(events.findById(event.id())).thenReturn(Mono.just(event));
        when(orders.create(any(), any())).thenAnswer(call -> Mono.just(call.<StateTransition>getArgument(0).order()));

        StepVerifier.create(new IssueComplimentaryUseCase(events, orders, replay, CLOCK).execute(command(event.id(), 20)))
                .assertNext(order -> assertThat(order.status()).isEqualTo(COMPLIMENTARY))
                .verifyComplete();
    }

    @Nested
    class Read {

        @Test
        void ownersCanReadTheirOrder() {
            Order order = reserved();
            when(orders.findById(order.id())).thenReturn(Mono.just(order));

            StepVerifier.create(getOrder.execute(order.id(), USER)).expectNext(order).verifyComplete();
        }

        @Test
        void otherCallersSeeNotFound() {
            Order order = reserved();
            when(orders.findById(order.id())).thenReturn(Mono.just(order));

            StepVerifier.create(getOrder.execute(order.id(), new UserId("someone-else")))
                    .expectError(NotFoundException.class)
                    .verify();
        }
    }

    @Nested
    class Confirm {

        private final ConfirmOrderUseCase confirmOrder = new ConfirmOrderUseCase(getOrder, orders, CLOCK);

        @Test
        void sellsAPendingOrder() {
            Order pending = reserved().startConfirmation(NOW).order();
            when(orders.findById(pending.id())).thenReturn(Mono.just(pending));
            when(orders.apply(any())).thenAnswer(call -> Mono.just(call.<StateTransition>getArgument(0).order()));

            StepVerifier.create(confirmOrder.execute(pending.id(), USER))
                    .assertNext(order -> assertThat(order.status()).isEqualTo(SOLD))
                    .verifyComplete();
        }

        @Test
        void confirmingASoldOrderAgainIsANoOp() {
            Order sold = reserved().startConfirmation(NOW).order().confirmSale(NOW).order();
            when(orders.findById(sold.id())).thenReturn(Mono.just(sold));

            StepVerifier.create(confirmOrder.execute(sold.id(), USER)).expectNext(sold).verifyComplete();
            verify(orders, never()).apply(any());
        }

        @Test
        void losingAConfirmationRaceStillAnswersSold() {
            Order pending = reserved().startConfirmation(NOW).order();
            Order sold = pending.confirmSale(NOW).order();
            when(orders.findById(pending.id())).thenReturn(Mono.just(pending), Mono.just(sold));
            when(orders.apply(any())).thenReturn(Mono.error(new OrderStateChangedException(pending.id(), PENDING_CONFIRMATION)));

            StepVerifier.create(confirmOrder.execute(pending.id(), USER)).expectNext(sold).verifyComplete();
        }

        @Test
        void aStateChangeToAnythingButSoldIsReported() {
            Order pending = reserved().startConfirmation(NOW).order();
            Order released = pending.release(pending.expiresAt()).order();
            when(orders.findById(pending.id())).thenReturn(Mono.just(pending), Mono.just(released));
            when(orders.apply(any())).thenReturn(Mono.error(new OrderStateChangedException(pending.id(), PENDING_CONFIRMATION)));

            StepVerifier.create(confirmOrder.execute(pending.id(), USER)).expectError(OrderStateChangedException.class).verify();
        }

        @Test
        void anOrderNotYetAcceptedByTheConsumerCannotBeConfirmed() {
            Order reserved = reserved();
            when(orders.findById(reserved.id())).thenReturn(Mono.just(reserved));

            StepVerifier.create(confirmOrder.execute(reserved.id(), USER)).expectError(IllegalTransitionException.class).verify();
        }
    }

    private static Order reserved() {
        return Order.place(new OrderId("order-1"), event(), USER, 2, NOW).order();
    }

    private static PlaceOrderCommand command(EventId eventId, int quantity) {
        return new PlaceOrderCommand(eventId, USER, quantity, KEY, "hash");
    }
}
