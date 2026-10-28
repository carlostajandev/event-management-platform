package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.Money;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.model.TicketState;
import com.nequi.ticketing.domain.model.UserId;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.PK;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.instant;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.integer;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.string;

/**
 * Order item: PK=ORDER#id. Orders that hold tickets also carry {@code holdStatus}, which places them in the
 * sparse holds-by-expiry index; the attribute is removed when the hold ends.
 */
final class OrderItems {

    static final String HOLDS_INDEX = "holds-by-expiry";

    private OrderItems() {
    }

    static Map<String, AttributeValue> key(OrderId id) {
        return Map.of(PK, s("ORDER#" + id.value()));
    }

    static TransactWriteItem create(String table, Order order) {
        return TransactWriteItem.builder().put(put -> put
                .tableName(table)
                .item(item(order))
                .conditionExpression("attribute_not_exists(PK)"))
                .build();
    }

    /**
     * Changes the status, conditional on the order still being in {@code from}. Moving into SOLD or
     * PENDING_CONFIRMATION also requires the deadline not to have passed; releasing requires that it has.
     * This is what settles races between the consumer, the confirm endpoint and the expiry job.
     */
    static TransactWriteItem transition(String table, StateTransition transition) {
        TicketState to = transition.to();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":from", s(transition.from().name()));
        values.put(":to", s(to.name()));
        values.put(":at", millis(transition.at()));

        String update = to.isHold()
                ? "SET #status = :to, holdStatus = :to, updatedAt = :at"
                : "SET #status = :to, updatedAt = :at REMOVE holdStatus";
        String condition = switch (to) {
            case PENDING_CONFIRMATION, SOLD -> "#status = :from AND expiresAt > :at";
            case AVAILABLE -> "#status = :from AND expiresAt <= :at";
            case RESERVED, COMPLIMENTARY -> "#status = :from";
        };

        return TransactWriteItem.builder().update(u -> u
                .tableName(table)
                .key(key(transition.order().id()))
                .updateExpression(update)
                .conditionExpression(condition)
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(values))
                .build();
    }

    static Map<String, AttributeValue> item(Order order) {
        Map<String, AttributeValue> item = new HashMap<>(key(order.id()));
        item.put("orderId", s(order.id().value()));
        item.put("eventId", s(order.eventId().value()));
        item.put("userId", s(order.userId().value()));
        item.put("quantity", n(order.quantity()));
        item.put("totalAmount", s(order.totalAmount().amount().toPlainString()));
        item.put("currency", s(order.totalAmount().currency().getCurrencyCode()));
        item.put("status", s(order.status().name()));
        item.put("createdAt", millis(order.createdAt()));
        item.put("updatedAt", millis(order.updatedAt()));
        if (order.expiresAt() != null) {
            item.put("expiresAt", millis(order.expiresAt()));
        }
        if (order.status().isHold()) {
            item.put("holdStatus", s(order.status().name()));
        }
        return item;
    }

    static Order fromItem(Map<String, AttributeValue> item) {
        return new Order(
                new OrderId(string(item, "orderId")),
                new EventId(string(item, "eventId")),
                new UserId(string(item, "userId")),
                integer(item, "quantity"),
                new Money(new BigDecimal(string(item, "totalAmount")), Currency.getInstance(string(item, "currency"))),
                TicketState.valueOf(string(item, "status")),
                instant(item, "createdAt"),
                instant(item, "expiresAt"),
                instant(item, "updatedAt"));
    }
}
