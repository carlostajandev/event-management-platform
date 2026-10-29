package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.Money;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.SK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.integer;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.string;

/** Event metadata item: PK=EVENT#id, SK=METADATA, listed by date through the events-by-date index. */
final class EventItems {

    static final String LISTING_INDEX = "events-by-date";
    static final String LISTING_KEY = "EVENT";

    private EventItems() {
    }

    static String partition(EventId id) {
        return "EVENT#" + id.value();
    }

    static Map<String, AttributeValue> key(EventId id) {
        return Map.of(PK, s(partition(id)), SK, s("METADATA"));
    }

    static Map<String, AttributeValue> item(Event event) {
        Map<String, AttributeValue> item = new HashMap<>(key(event.id()));
        item.put("eventId", s(event.id().value()));
        item.put("name", s(event.name()));
        item.put("venue", s(event.venue()));
        item.put("eventDate", millis(event.date()));
        item.put("capacity", n(event.capacity()));
        item.put("ticketPrice", s(event.ticketPrice().amount().toPlainString()));
        item.put("currency", s(event.ticketPrice().currency().getCurrencyCode()));
        item.put("createdAt", millis(event.createdAt()));
        item.put("listingKey", s(LISTING_KEY));
        return item;
    }

    static Event fromItem(Map<String, AttributeValue> item) {
        return new Event(
                new EventId(string(item, "eventId")),
                string(item, "name"),
                string(item, "venue"),
                instant(item, "eventDate"),
                integer(item, "capacity"),
                new Money(new BigDecimal(string(item, "ticketPrice")), Currency.getInstance(string(item, "currency"))),
                instant(item, "createdAt"));
    }
}
