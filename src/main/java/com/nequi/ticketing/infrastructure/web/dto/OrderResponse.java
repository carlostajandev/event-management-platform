package com.nequi.ticketing.infrastructure.web.dto;

import com.nequi.ticketing.domain.model.Order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        String orderId,
        String eventId,
        String userId,
        int quantity,
        BigDecimal totalAmount,
        String currency,
        String status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id().value(), order.eventId().value(), order.userId().value(),
                order.quantity(), order.totalAmount().amount(), order.totalAmount().currency().getCurrencyCode(),
                order.status().name(), order.expiresAt(), order.createdAt(), order.updatedAt());
    }
}
