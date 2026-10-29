package com.nequi.ticketing.application.order;

import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.TicketState;
import com.nequi.ticketing.domain.model.UserId;
import com.nequi.ticketing.domain.port.OrderRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Simulated payment confirmation: PENDING_CONFIRMATION -> SOLD. Confirming an order that is already SOLD returns
 * it unchanged, so the call is safe to retry, including two confirmations racing each other. An order still
 * RESERVED has not been accepted by the consumer yet.
 */
public final class ConfirmOrderUseCase {

    private final GetOrderUseCase getOrder;
    private final OrderRepository orders;
    private final Clock clock;

    public ConfirmOrderUseCase(GetOrderUseCase getOrder, OrderRepository orders, Clock clock) {
        this.getOrder = getOrder;
        this.orders = orders;
        this.clock = clock;
    }

    public Mono<Order> execute(OrderId id, UserId caller) {
        return getOrder.execute(id, caller)
                .flatMap(order -> order.status() == TicketState.SOLD
                        ? Mono.just(order)
                        : orders.apply(order.confirmSale(clock.instant()))
                                .onErrorResume(OrderStateChangedException.class, changed -> getOrder.execute(id, caller)
                                        .filter(current -> current.status() == TicketState.SOLD)
                                        .switchIfEmpty(Mono.error(changed))));
    }
}
