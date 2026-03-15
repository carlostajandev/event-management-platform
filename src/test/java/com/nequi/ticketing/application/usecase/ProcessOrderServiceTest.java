package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.service.TicketStateMachine;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.domain.valueobject.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessOrderService")
class ProcessOrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private TicketRepository ticketRepository;

    private ProcessOrderService service;

    @BeforeEach
    void setUp() {
        service = new ProcessOrderService(orderRepository, ticketRepository,
                new TicketStateMachine());
    }

    @Test
    @DisplayName("should confirm order and sell tickets successfully")
    void shouldConfirmOrderAndSellTickets() {
        String ticketId = "tkt_001";
        Order pendingOrder = pendingOrder(List.of(ticketId));
        Ticket reservedTicket = reservedTicket(ticketId);

        when(orderRepository.findById(any())).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(ticketRepository.findById(TicketId.of(ticketId)))
                .thenReturn(Mono.just(reservedTicket));
        when(ticketRepository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.process(pendingOrder.orderId().value()))
                .verifyComplete();

        // Verify order went through PROCESSING → CONFIRMED
        verify(orderRepository).update(argThat(o ->
                o.status() == OrderStatus.PROCESSING));
        verify(orderRepository).update(argThat(o ->
                o.status() == OrderStatus.CONFIRMED));
    }

    @Test
    @DisplayName("should mark order failed and release tickets when ticket not found")
    void shouldFailAndReleaseTicketsWhenTicketNotFound() {
        String ticketId = "tkt_missing";
        Order pendingOrder = pendingOrder(List.of(ticketId));

        when(orderRepository.findById(any())).thenReturn(Mono.just(pendingOrder));
        when(orderRepository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(ticketRepository.findById(TicketId.of(ticketId))).thenReturn(Mono.empty());

        StepVerifier.create(service.process(pendingOrder.orderId().value()))
                .verifyComplete();

        verify(orderRepository).update(argThat(o -> o.status() == OrderStatus.FAILED));
    }

    @Test
    @DisplayName("should skip processing when order already in final state")
    void shouldSkipWhenOrderAlreadyFinal() {
        Order confirmedOrder = new Order(
                OrderId.of("ord_done"),
                EventId.of("evt_test"),
                "usr_123",
                List.of("tkt_001"),
                1,
                Money.ofCOP(new BigDecimal("350000")),
                OrderStatus.CONFIRMED,
                null,
                Instant.now(), Instant.now(), 1L
        );

        when(orderRepository.findById(any())).thenReturn(Mono.just(confirmedOrder));

        StepVerifier.create(service.process("ord_done"))
                .verifyComplete();

        // No updates should happen
        verify(orderRepository, org.mockito.Mockito.never()).update(any());
    }

    @Test
    @DisplayName("should fail when order not found")
    void shouldFailWhenOrderNotFound() {
        when(orderRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.process("ord_unknown"))
                .expectErrorMatches(ex -> ex.getMessage().contains("Order not found"))
                .verify();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Order pendingOrder(List<String> ticketIds) {
        return new Order(
                OrderId.of("ord_test123"),
                EventId.of("evt_test456"),
                "usr_789",
                ticketIds,
                ticketIds.size(),
                Money.ofCOP(new BigDecimal("350000")),
                OrderStatus.PENDING,
                null,
                Instant.now(), Instant.now(), 0L
        );
    }

    private Ticket reservedTicket(String ticketId) {
        return new Ticket(
                TicketId.of(ticketId),
                EventId.of("evt_test456"),
                "usr_789",
                "ord_test123",
                TicketStatus.RESERVED,
                Money.ofCOP(new BigDecimal("350000")),
                Instant.now(),
                Instant.now().plus(10, ChronoUnit.MINUTES),
                null,
                Instant.now(), Instant.now(), 1L
        );
    }
}