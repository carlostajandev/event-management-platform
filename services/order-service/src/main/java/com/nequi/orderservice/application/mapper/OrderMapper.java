package com.nequi.orderservice.application.mapper;

import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.shared.domain.model.Order;
import org.springframework.stereotype.Component;

/**
 * Maps between {@link Order} domain model and order-service DTOs.
 *
 * <p>Rule: no use case maps inline. All conversions go through this class.
 * <ul>
 *   <li>{@link #toResponse} — outbound: domain model → response DTO</li>
 * </ul>
 */
@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.id(),
                order.reservationId(),
                order.eventId(),
                order.userId(),
                order.seatsCount(),
                order.totalAmount(),
                order.currency(),
                order.status(),
                order.createdAt(),
                order.updatedAt()
        );
    }
}
