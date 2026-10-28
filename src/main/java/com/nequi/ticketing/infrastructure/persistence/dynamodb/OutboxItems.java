package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.Order;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.time.Instant;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;

/**
 * Outbox item written with the order, so enqueuing is part of the same transaction (no dual write).
 * Unpublished items carry {@code pendingKey} and appear in the sparse pending-by-time index.
 */
final class OutboxItems {

    static final String ORDER_PLACED = "OrderPlaced";
    static final String PENDING = "PENDING";

    private OutboxItems() {
    }

    static TransactWriteItem orderPlaced(String table, Order order, Instant at) {
        Map<String, AttributeValue> item = Map.of(
                PK, s("OUTBOX#" + ORDER_PLACED + "#" + order.id().value()),
                "messageType", s(ORDER_PLACED),
                "orderId", s(order.id().value()),
                "eventId", s(order.eventId().value()),
                "createdAt", millis(at),
                "pendingKey", s(PENDING));
        return TransactWriteItem.builder().put(put -> put
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)"))
                .build();
    }
}
