package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.OrderId;

import java.time.Instant;
import java.util.List;

/**
 * Core domain entity representing a purchase order.
 *
 * <p>An order is created when a user requests tickets.
 * It starts as PENDING, gets enqueued in SQS, and is processed
 * asynchronously by the order consumer.
 *
 * @param orderId      Unique identifier (ord_UUID)
 * @param eventId      The event tickets are for
 * @param userId       The purchasing user
 * @param ticketIds    IDs of reserved tickets
 * @param quantity     Number of tickets
 * @param totalAmount  Total price
 * @param status       Current order status
 * @param failureReason Reason for failure (null if not FAILED)
 * @param createdAt    Creation timestamp
 * @param updatedAt    Last update timestamp
 * @param version      Optimistic locking version
 */
public record Order(
        OrderId orderId,
        EventId eventId,
        String userId,
        List<String> ticketIds,
        int quantity,
        Money totalAmount,
        OrderStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    /**
     * Creates a new pending order.
     */
    public static Order create(
            OrderId orderId,
            EventId eventId,
            String userId,
            List<String> ticketIds,
            int quantity,
            Money totalAmount) {

        Instant now = Instant.now();
        return new Order(
                orderId, eventId, userId,
                List.copyOf(ticketIds),
                quantity, totalAmount,
                OrderStatus.PENDING,
                null,
                now, now, 0L
        );
    }

    public Order markProcessing() {
        Instant now = Instant.now();
        return new Order(orderId, eventId, userId, ticketIds, quantity, totalAmount,
                OrderStatus.PROCESSING, null, createdAt, now, version + 1);
    }

    public Order markConfirmed() {
        Instant now = Instant.now();
        return new Order(orderId, eventId, userId, ticketIds, quantity, totalAmount,
                OrderStatus.CONFIRMED, null, createdAt, now, version + 1);
    }

    public Order markFailed(String reason) {
        Instant now = Instant.now();
        return new Order(orderId, eventId, userId, ticketIds, quantity, totalAmount,
                OrderStatus.FAILED, reason, createdAt, now, version + 1);
    }

    public Order markCancelled() {
        Instant now = Instant.now();
        return new Order(orderId, eventId, userId, ticketIds, quantity, totalAmount,
                OrderStatus.CANCELLED, null, createdAt, now, version + 1);
    }
}
