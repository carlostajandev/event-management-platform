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
 * <p>Processing flow:
 * <ol>
 *   <li>Load order from DynamoDB</li>
 *   <li>Mark order as PROCESSING</li>
 *   <li>Validate all reserved tickets still exist</li>
 *   <li>Move tickets: RESERVED → PENDING_CONFIRMATION → SOLD</li>
 *   <li>Mark order as CONFIRMED</li>
 * </ol>
 *
 * <p>On any failure:
 * <ol>
 *   <li>Release all tickets back to AVAILABLE</li>
 *   <li>Mark order as FAILED with reason</li>
 * </ol>
 *
 * <p>This service is idempotent — if called twice with the same orderId,
 * the second call is a no-op because the order is already in a final state.
 */
@Service
public class ProcessOrderService {

    private static final Logger log = LoggerFactory.getLogger(ProcessOrderService.class);

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

    /**
     * Processes a purchase order message from SQS.
     *
     * @param orderId the order to process
     * @return completed Mono when processing is done
     */
    public Mono<Void> process(String orderId) {
        log.info("Processing order: orderId={}", orderId);

        return orderRepository.findById(OrderId.of(orderId))
                .switchIfEmpty(Mono.error(
                        new RuntimeException("Order not found: " + orderId)))
                .flatMap(order -> {
                    // Idempotency — skip if already in final state
                    if (order.status().isFinal()) {
                        log.info("Order already in final state: orderId={}, status={}",
                                orderId, order.status());
                        return Mono.empty();
                    }
                    return processOrder(order);
                });
    }

    private Mono<Void> processOrder(Order order) {
        // Mark as PROCESSING
        return orderRepository.update(order.markProcessing())
                .flatMap(processing -> confirmTickets(processing)
                        .then(orderRepository.update(processing.markConfirmed()))
                        .doOnSuccess(confirmed -> log.info(
                                "Order confirmed: orderId={}", confirmed.orderId().value()))
                        .onErrorResume(ex -> handleFailure(processing, ex)))
                .then();
    }

    private Mono<Void> confirmTickets(Order order) {
        return Flux.fromIterable(order.ticketIds())
                .flatMap(ticketId -> ticketRepository
                        .findById(TicketId.of(ticketId))
                        .switchIfEmpty(Mono.error(
                                new RuntimeException("Ticket not found: " + ticketId)))
                        .flatMap(ticket -> {
                            // RESERVED → PENDING_CONFIRMATION → SOLD
                            stateMachine.validateTransition(
                                    ticket.status(), TicketStatus.PENDING_CONFIRMATION);
                            Ticket pending = ticket.confirmPending();
                            return ticketRepository.update(pending);
                        })
                        .flatMap(pending -> {
                            stateMachine.validateTransition(
                                    pending.status(), TicketStatus.SOLD);
                            return ticketRepository.update(pending.sell());
                        }))
                .then();
    }

    private Mono<Order> handleFailure(Order order, Throwable ex) {
        log.error("Order processing failed: orderId={}, reason={}",
                order.orderId().value(), ex.getMessage());

        // Release all tickets back to AVAILABLE
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
                        }))
                .then(orderRepository.update(order.markFailed(ex.getMessage())));
    }
}
