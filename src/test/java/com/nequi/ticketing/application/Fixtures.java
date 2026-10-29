package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.Money;
import com.nequi.ticketing.domain.model.UserId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-11-01T15:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final UserId USER = new UserId("user-1");
    public static final IdempotencyKey KEY = new IdempotencyKey(USER.value(), "key-1");

    private Fixtures() {
    }

    public static Event event() {
        return Event.create(new EventId("event-1"), "Rock Fest", "Movistar Arena", NOW.plus(Duration.ofDays(30)), 100,
                Money.of("150000", "COP"), NOW);
    }
}
