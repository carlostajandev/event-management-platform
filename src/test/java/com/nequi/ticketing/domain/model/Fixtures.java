package com.nequi.ticketing.domain.model;

import java.time.Duration;
import java.time.Instant;

final class Fixtures {

    static final Instant NOW = Instant.parse("2026-11-01T15:00:00Z");
    static final EventId EVENT_ID = new EventId("event-1");
    static final UserId USER = new UserId("user-1");

    private Fixtures() {
    }

    static Event event(int capacity) {
        return Event.create(EVENT_ID, "Rock Fest", "Movistar Arena", NOW.plus(Duration.ofDays(30)), capacity,
                Money.of("150000", "COP"), NOW);
    }

    static Order reservedOrder(int quantity) {
        return Order.place(new OrderId("order-1"), event(100), USER, quantity, NOW).order();
    }
}
