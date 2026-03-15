package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReleaseExpiredReservationsService")
class ReleaseExpiredReservationsServiceTest {

    @Mock private TicketRepository ticketRepository;

    private ReleaseExpiredReservationsService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseExpiredReservationsService(ticketRepository);
    }

    @Test
    @DisplayName("should release expired reservations and return count")
    void shouldReleaseExpiredReservations() {
        Ticket expired1 = expiredTicket("tkt_001");
        Ticket expired2 = expiredTicket("tkt_002");

        when(ticketRepository.findExpiredReservations(any()))
                .thenReturn(Flux.just(expired1, expired2));
        when(ticketRepository.update(any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.execute())
                .assertNext(count -> assertThat(count).isEqualTo(2L))
                .verifyComplete();

        verify(ticketRepository, times(2)).update(any());
    }

    @Test
    @DisplayName("should return 0 when no expired reservations found")
    void shouldReturnZeroWhenNoExpiredReservations() {
        when(ticketRepository.findExpiredReservations(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.execute())
                .assertNext(count -> assertThat(count).isEqualTo(0L))
                .verifyComplete();
    }

    @Test
    @DisplayName("should release ticket back to AVAILABLE status")
    void shouldReleaseTicketToAvailable() {
        Ticket expired = expiredTicket("tkt_001");

        when(ticketRepository.findExpiredReservations(any()))
                .thenReturn(Flux.just(expired));
        when(ticketRepository.update(any()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.execute())
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        verify(ticketRepository).update(any(Ticket.class));
    }

    private Ticket expiredTicket(String ticketId) {
        return new Ticket(
                TicketId.of(ticketId),
                EventId.of("evt_test"),
                "usr_123",
                "ord_456",
                TicketStatus.RESERVED,
                Money.ofCOP(new BigDecimal("350000")),
                Instant.now().minus(15, ChronoUnit.MINUTES),
                Instant.now().minus(5, ChronoUnit.MINUTES),
                null,
                Instant.now().minus(15, ChronoUnit.MINUTES),
                Instant.now().minus(15, ChronoUnit.MINUTES),
                1L
        );
    }
}