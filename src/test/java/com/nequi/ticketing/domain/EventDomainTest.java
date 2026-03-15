package com.nequi.ticketing.domain;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Event domain model")
class EventDomainTest {

    @Test
    @DisplayName("should create event with correct initial state")
    void shouldCreateEventWithCorrectInitialState() {
        Event event = validEvent(1000);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventId().value()).startsWith("evt_");
        assertThat(event.status()).isEqualTo(Event.EventStatus.DRAFT);
        assertThat(event.availableTickets()).isEqualTo(1000);
        assertThat(event.totalCapacity()).isEqualTo(1000);
        assertThat(event.version()).isEqualTo(0L);
    }

    @Test
    @DisplayName("should publish event and increment version")
    void shouldPublishEvent() {
        Event event = validEvent(100);
        Event published = event.publish();

        assertThat(published.status()).isEqualTo(Event.EventStatus.PUBLISHED);
        assertThat(published.version()).isEqualTo(1L);
        assertThat(published.eventId()).isEqualTo(event.eventId());
    }

    @Test
    @DisplayName("should reserve tickets and decrement available count")
    void shouldReserveTickets() {
        Event event = validEvent(100).publish();
        Event reserved = event.reserveTickets(10);

        assertThat(reserved.availableTickets()).isEqualTo(90);
        assertThat(reserved.version()).isEqualTo(event.version() + 1);
    }

    @Test
    @DisplayName("should mark event as SOLD_OUT when last tickets reserved")
    void shouldMarkAsSoldOutWhenLastTicketsReserved() {
        Event event = validEvent(10).publish();
        Event soldOut = event.reserveTickets(10);

        assertThat(soldOut.availableTickets()).isEqualTo(0);
        assertThat(soldOut.status()).isEqualTo(Event.EventStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("should release tickets and restore availability")
    void shouldReleaseTickets() {
        Event event = validEvent(10).publish();
        Event reserved = event.reserveTickets(10);
        Event released = reserved.releaseTickets(5);

        assertThat(released.availableTickets()).isEqualTo(5);
        assertThat(released.status()).isEqualTo(Event.EventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("should throw when reserving more tickets than available")
    void shouldThrowWhenNotEnoughTickets() {
        Event event = validEvent(5).publish();

        assertThatThrownBy(() -> event.reserveTickets(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not enough tickets");
    }

    @Test
    @DisplayName("should throw when creating event with past date")
    void shouldThrowWhenEventDateInPast() {
        assertThatThrownBy(() -> Event.create(
                "Past Event", "desc",
                Instant.now().minus(1, ChronoUnit.DAYS),
                Venue.of("V", "C", "CO"),
                100,
                Money.ofCOP(new BigDecimal("100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    @DisplayName("should throw when creating event with zero capacity")
    void shouldThrowWhenCapacityIsZero() {
        assertThatThrownBy(() -> Event.create(
                "Event", "desc",
                Instant.now().plus(1, ChronoUnit.DAYS),
                Venue.of("V", "C", "CO"),
                0,
                Money.ofCOP(new BigDecimal("100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    private Event validEvent(int capacity) {
        return Event.create(
                "Test Event",
                "Description",
                Instant.now().plus(30, ChronoUnit.DAYS),
                Venue.of("Estadio El Campín", "Bogotá", "Colombia"),
                capacity,
                Money.ofCOP(new BigDecimal("350000.00"))
        );
    }
}
