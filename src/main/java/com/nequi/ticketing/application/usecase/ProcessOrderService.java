package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.service.TicketStateMachine;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.domain.valueobject.TicketId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Processes purchase orders consumed from SQS.
 *
 * <p>Idempotent — if called twice for the same orderId, the second call
 * detects the final state and exits immediately (no-op).
 *
 * <p>DynamoDB concurrency limit: {@code flatMap(fn, 4)} caps concurrent
 * writes to 4 per order. Without a limit, an order with 10 tickets would
 * fire 10 DynamoDB writes simultaneously — under load this creates hot
 * partitions and increases ProvisionedThroughputExceededException probability.
 * 4 concurrent writes is a balanced trade-off between throughput and safety.
 */
@Service
public class ProcessOrderService {

    private static final Logger log = LoggerFactory.getLogger(ProcessOrderService.class);

    /** Max concurrent DynamoDB writes per order — prevents hot partition exhaustion. */
    private static final int MAX_CONCURRENT_TICKET_WRITES = 4;

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final TicketStateMachine stateMachine;

    public ProcessOrderService(
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            TicketStateMachine stateMachine) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.stateMachine = stateMachine;
    }

    public Mono<Void> process(String orderId) {
        log.info("Processing order: orderId={}", orderId);

        return orderRepository.findById(OrderId.of(orderId))
                .switchIfEmpty(Mono.error(
                        new RuntimeException("Order not found: " + orderId)))
                .flatMap(order -> {
                    if (order.status().isFinal()) {
                        log.info("Order already in final state: orderId={}, status={}",
                                orderId, order.status());
                        return Mono.empty();
                    }
                    return processOrder(order);
                });
    }

    private Mono<Void> processOrder(Order order) {
        return orderRepository.update(order.markProcessing())
                .flatMap(processing -> confirmTickets(processing)
                        .then(orderRepository.update(processing.markConfirmed()))
                        .doOnSuccess(confirmed -> log.info(
                                "Order confirmed: orderId={}", confirmed.orderId().value()))
                        .onErrorResume(ex -> handleFailure(processing, ex)))
                .then();
    }

    /**
     * Confirms all tickets for an order with bounded concurrency.
     *
     * <p>Uses {@code flatMap(fn, MAX_CONCURRENT_TICKET_WRITES)} to limit
     * simultaneous DynamoDB writes. This prevents hot partition exhaustion
     * when processing large orders under high traffic.
     */
    private Mono<Void> confirmTickets(Order order) {
        return Flux.fromIterable(order.ticketIds())
                .flatMap(ticketId -> ticketRepository
                                .findById(TicketId.of(ticketId))
                                .switchIfEmpty(Mono.error(
                                        new RuntimeException("Ticket not found: " + ticketId)))
                                .flatMap(ticket -> {
                                    stateMachine.validateTransition(
                                            ticket.status(), TicketStatus.PENDING_CONFIRMATION);
                                    return ticketRepository.update(ticket.confirmPending());
                                })
                                .flatMap(pending -> {
                                    stateMachine.validateTransition(
                                            pending.status(), TicketStatus.SOLD);
                                    return ticketRepository.update(pending.sell());
                                }),
                        MAX_CONCURRENT_TICKET_WRITES) // bounded concurrency
                .then();
    }

    private Mono<Order> handleFailure(Order order, Throwable ex) {
        log.error("Order processing failed: orderId={}, reason={}",
                order.orderId().value(), ex.getMessage());

        return Flux.fromIterable(order.ticketIds())
                .flatMap(ticketId -> ticketRepository
                                .findById(TicketId.of(ticketId))
                                .flatMap(ticket -> {
                                    if (ticket.status() != TicketStatus.SOLD
                                            && ticket.status() != TicketStatus.AVAILABLE) {
                                        return ticketRepository.update(ticket.release());
                                    }
                                    return Mono.just(ticket);
                                })
                                .onErrorResume(releaseEx -> {
                                    log.warn("Failed to release ticket {}: {}",
                                            ticketId, releaseEx.getMessage());
                                    return Mono.empty();
                                }),
                        MAX_CONCURRENT_TICKET_WRITES)
                .then(orderRepository.update(order.markFailed(ex.getMessage())));
    }
}