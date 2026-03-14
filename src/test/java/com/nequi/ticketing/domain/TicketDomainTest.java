package com.nequi.ticketing.domain;

import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ticket domain model")
class TicketDomainTest {

    @Test
    @DisplayName("should create available ticket with correct initial state")
    void shouldCreateAvailableTicket() {
        Ticket ticket = availableTicket();

        assertThat(ticket.ticketId().value()).startsWith("tkt_");
        assertThat(ticket.status()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(ticket.userId()).isNull();
        assertThat(ticket.orderId()).isNull();
        assertThat(ticket.reservedAt()).isNull();
        assertThat(ticket.expiresAt()).isNull();
        assertThat(ticket.version()).isEqualTo(0L);
    }

    @Test
    @DisplayName("should reserve ticket and set expiry")
    void shouldReserveTicket() {
        Ticket ticket = availableTicket().reserve("user_123", "ord_456", 10);

        assertThat(ticket.status()).isEqualTo(TicketStatus.RESERVED);
        assertThat(ticket.userId()).isEqualTo("user_123");
        assertThat(ticket.orderId()).isEqualTo("ord_456");
        assertThat(ticket.reservedAt()).isNotNull();
        assertThat(ticket.expiresAt()).isNotNull();
        assertThat(ticket.expiresAt()).isAfter(ticket.reservedAt());
        assertThat(ticket.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should move to PENDING_CONFIRMATION after reserve")
    void shouldConfirmPending() {
        Ticket ticket = availableTicket()
                .reserve("user_123", "ord_456", 10)
                .confirmPending();

        assertThat(ticket.status()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(ticket.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("should sell ticket and set confirmedAt")
    void shouldSellTicket() {
        Ticket ticket = availableTicket()
                .reserve("user_123", "ord_456", 10)
                .confirmPending()
                .sell();

        assertThat(ticket.status()).isEqualTo(TicketStatus.SOLD);
        assertThat(ticket.confirmedAt()).isNotNull();
        assertThat(ticket.version()).isEqualTo(3L);
    }

    @Test
    @DisplayName("should release ticket back to available")
    void shouldReleaseTicket() {
        Ticket ticket = availableTicket()
                .reserve("user_123", "ord_456", 10)
                .release();

        assertThat(ticket.status()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(ticket.userId()).isNull();
        assertThat(ticket.orderId()).isNull();
        assertThat(ticket.expiresAt()).isNull();
        assertThat(ticket.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("should grant complimentary ticket")
    void shouldGrantComplimentary() {
        Ticket ticket = availableTicket().grantComplimentary("vip_user");

        assertThat(ticket.status()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(ticket.userId()).isEqualTo("vip_user");
        assertThat(ticket.confirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("should detect expired reservation")
    void shouldDetectExpiredReservation() {
        Ticket ticket = availableTicket().reserve("user_123", "ord_456", 0);

        // TTL = 0 minutes means it expires immediately
        assertThat(ticket.isExpired()).isTrue();
    }

    private Ticket availableTicket() {
        return Ticket.createAvailable(
                EventId.of("evt_test123"),
                Money.ofCOP(new BigDecimal("350000.00"))
        );
    }
}
