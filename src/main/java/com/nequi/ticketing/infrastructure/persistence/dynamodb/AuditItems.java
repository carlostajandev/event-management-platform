package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.model.TicketState;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.SK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;

/**
 * Audit item: PK=ORDER#id, SK=epochMillis#step#toState. One per transition, written in the same transaction.
 * The lifecycle step keeps the trail in order even when two transitions share a millisecond.
 */
final class AuditItems {

    private AuditItems() {
    }

    static TransactWriteItem create(String table, StateTransition transition) {
        Map<String, AttributeValue> item = Map.of(
                PK, s("ORDER#" + transition.order().id().value()),
                SK, s("%013d#%d#%s".formatted(transition.at().toEpochMilli(), step(transition.to()), transition.to().name())),
                "eventId", s(transition.order().eventId().value()),
                "userId", s(transition.order().userId().value()),
                "fromState", s(transition.from().name()),
                "toState", s(transition.to().name()),
                "quantity", n(transition.move().quantity()),
                "at", millis(transition.at()));
        return TransactWriteItem.builder().put(put -> put.tableName(table).item(item)).build();
    }

    private static int step(TicketState to) {
        return switch (to) {
            case RESERVED, COMPLIMENTARY -> 1;
            case PENDING_CONFIRMATION -> 2;
            case SOLD, AVAILABLE -> 3;
        };
    }
}
