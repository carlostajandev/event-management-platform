package com.nequi.ticketing.application.dto;

import com.nequi.ticketing.domain.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Output DTO for Order responses.
 */
public record OrderResponse(
        String orderId,
        String eventId,
        String userId,
        List<String> ticketIds,
        int quantity,
        BigDecimal totalAmount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.orderId().value(),
                order.eventId().value(),
                order.userId(),
                order.ticketIds(),
                order.quantity(),
                order.totalAmount().amount(),
                order.totalAmount().currency(),
                order.status().name(),
                order.failureReason(),
                order.createdAt(),
                order.updatedAt()
        );
    }
}
