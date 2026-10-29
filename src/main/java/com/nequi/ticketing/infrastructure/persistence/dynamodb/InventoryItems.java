package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.model.InventoryMove;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.util.HashMap;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.SK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.counter;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.integer;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;

/** Inventory item: PK=EVENT#id, SK=INVENTORY, one numeric counter per ticket state. */
final class InventoryItems {

    private InventoryItems() {
    }

    static Map<String, AttributeValue> key(EventId eventId) {
        return Map.of(PK, s(EventItems.partition(eventId)), SK, s("INVENTORY"));
    }

    static Map<String, AttributeValue> item(Inventory inventory) {
        Map<String, AttributeValue> item = new HashMap<>(key(inventory.eventId()));
        item.put("eventId", s(inventory.eventId().value()));
        item.put("total", n(inventory.total()));
        item.put("available", n(inventory.available()));
        item.put("reserved", n(inventory.reserved()));
        item.put("pendingConfirmation", n(inventory.pendingConfirmation()));
        item.put("sold", n(inventory.sold()));
        item.put("complimentary", n(inventory.complimentary()));
        return item;
    }

    /**
     * Moves tickets between two counters, conditional on the source counter holding enough tickets.
     * This condition is what prevents overselling: it is evaluated atomically by DynamoDB.
     * On failure the current item is returned so the caller can report the available count.
     */
    static TransactWriteItem move(String table, EventId eventId, InventoryMove move) {
        return TransactWriteItem.builder().update(update -> update
                .tableName(table)
                .key(key(eventId))
                .updateExpression("SET #from = #from - :n, #to = #to + :n")
                .conditionExpression("#from >= :n")
                .expressionAttributeNames(Map.of("#from", counter(move.from()), "#to", counter(move.to())))
                .expressionAttributeValues(Map.of(":n", n(move.quantity())))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD))
                .build();
    }

    static Inventory fromItem(Map<String, AttributeValue> item) {
        return new Inventory(
                new EventId(Attributes.string(item, "eventId")),
                integer(item, "total"),
                integer(item, "available"),
                integer(item, "reserved"),
                integer(item, "pendingConfirmation"),
                integer(item, "sold"),
                integer(item, "complimentary"));
    }
}
