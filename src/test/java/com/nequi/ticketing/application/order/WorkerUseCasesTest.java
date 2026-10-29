package com.nequi.ticketing.application.order;

import com.nequi.ticketing.application.order.ProcessPlacedOrderUseCase.Outcome;
import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.model.TicketState;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.support.MutableClock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static com.nequi.ticketing.application.Fixtures.CLOCK;
import static com.nequi.ticketing.application.Fixtures.NOW;
import static com.nequi.ticketing.application.Fixtures.USER;
import static com.nequi.ticketing.application.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerUseCasesTest {

    private final OrderRepository orders = mock(OrderRepository.class);

    @Nested
    class ProcessPlacedOrder {

        private final ProcessPlacedOrderUseCase process = new ProcessPlacedOrderUseCase(orders, CLOCK);

        @Test
        void acceptsAReservedOrderWithinItsHold() {
            Order reserved = reserved(NOW);
            when(orders.findById(reserved.id())).thenReturn(Mono.just(reserved));
            when(orders.apply(any())).thenAnswer(call -> Mono.just(call.<StateTransition>getArgument(0).order()));

            StepVerifier.create(process.execute(reserved.id())).expectNext(Outcome.ACCEPTED).verifyComplete();

            ArgumentCaptor<StateTransition> transition = ArgumentCaptor.forClass(StateTransition.class);
            verify(orders).apply(transition.capture());
            assertThat(transition.getValue().to()).isEqualTo(TicketState.PENDING_CONFIRMATION);
        }

        @Test
        void acknowledgesDuplicatesLateMessagesAndUnknownOrdersWithoutWriting() {
            Order accepted = reserved(NOW).startConfirmation(NOW).order();
            Order expired = Order.place(new OrderId("order-late"), event(), USER, 1, NOW.minus(Order.HOLD_DURATION)).order();
            OrderId unknown = new OrderId("unknown");
            when(orders.findById(accepted.id())).thenReturn(Mono.just(accepted));
            when(orders.findById(expired.id())).thenReturn(Mono.just(expired));
            when(orders.findById(unknown)).thenReturn(Mono.empty());

            StepVerifier.create(process.execute(accepted.id())).expectNext(Outcome.ALREADY_PROCESSED).verifyComplete();
            StepVerifier.create(process.execute(expired.id())).expectNext(Outcome.EXPIRED).verifyComplete();
            StepVerifier.create(process.execute(unknown)).expectNext(Outcome.NOT_FOUND).verifyComplete();
            verify(orders, never()).apply(any());
        }

        @Test
        void losingARaceIsAlreadyProcessed() {
            Order reserved = reserved(NOW);
            when(orders.findById(reserved.id())).thenReturn(Mono.just(reserved));
            when(orders.apply(any())).thenReturn(Mono.error(new OrderStateChangedException(reserved.id(), TicketState.RESERVED)));

            StepVerifier.create(process.execute(reserved.id())).expectNext(Outcome.ALREADY_PROCESSED).verifyComplete();
        }

        @Test
        void technicalFailuresPropagateSoTheMessageIsRedelivered() {
            Order reserved = reserved(NOW);
            when(orders.findById(reserved.id())).thenReturn(Mono.just(reserved));
            when(orders.apply(any())).thenReturn(Mono.error(new IllegalStateException("DynamoDB unavailable")));

            StepVerifier.create(process.execute(reserved.id())).expectError(IllegalStateException.class).verify();
        }
    }

    @Nested
    class ReleaseExpiredHolds {

        @Test
        void releasesExpiredHoldsAndSkipsOrdersThatChanged() {
            Order expired = reserved(NOW.minus(Order.HOLD_DURATION));
            Order changed = Order.place(new OrderId("order-2"), event(), USER, 1, NOW.minus(Order.HOLD_DURATION)).order();
            when(orders.findExpiredHolds(NOW, 50)).thenReturn(Flux.just(expired, changed));
            when(orders.apply(any())).thenAnswer(call -> {
                StateTransition transition = call.getArgument(0);
                return transition.order().id().equals(changed.id())
                        ? Mono.error(new OrderStateChangedException(changed.id(), TicketState.RESERVED))
                        : Mono.just(transition.order());
            });

            StepVerifier.create(new ReleaseExpiredHoldsUseCase(orders, CLOCK).execute(50)).expectNext(1L).verifyComplete();
        }

        @Test
        void readsTheClockOnEverySubscription() {
            MutableClock clock = new MutableClock();
            when(orders.findExpiredHolds(any(), anyInt())).thenReturn(Flux.empty());
            Mono<Long> run = new ReleaseExpiredHoldsUseCase(orders, clock).execute(10);

            run.block();
            clock.advance(Duration.ofMinutes(10));
            run.block();

            ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
            verify(orders, times(2)).findExpiredHolds(now.capture(), eq(10));
            assertThat(Duration.between(now.getAllValues().get(0), now.getAllValues().get(1)))
                    .isGreaterThanOrEqualTo(Duration.ofMinutes(10));
        }
    }

    private static Order reserved(Instant placedAt) {
        return Order.place(new OrderId("order-1"), event(), USER, 1, placedAt).order();
    }
}
