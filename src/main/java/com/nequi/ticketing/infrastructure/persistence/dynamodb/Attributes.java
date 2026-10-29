package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.TicketState;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

/** Small helpers to build and read DynamoDB attribute maps. */
final class Attributes {

    static final String PK = "PK";
    static final String SK = "SK";

    private Attributes() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static AttributeValue millis(Instant instant) {
        return n(instant.toEpochMilli());
    }

    static String string(Map<String, AttributeValue> item, String name) {
        return item.get(name).s();
    }

    static int integer(Map<String, AttributeValue> item, String name) {
        return Integer.parseInt(item.get(name).n());
    }

    static Instant instant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    /** Name of the inventory counter attribute that holds tickets in {@code state}. */
    static String counter(TicketState state) {
        return switch (state) {
            case AVAILABLE -> "available";
            case RESERVED -> "reserved";
            case PENDING_CONFIRMATION -> "pendingConfirmation";
            case SOLD -> "sold";
            case COMPLIMENTARY -> "complimentary";
        };
    }
}
