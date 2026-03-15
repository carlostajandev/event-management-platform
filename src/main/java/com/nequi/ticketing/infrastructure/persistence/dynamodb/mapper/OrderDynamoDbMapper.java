package com.nequi.ticketing.infrastructure.persistence.dynamodb.mapper;

import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.OrderDynamoDbEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OrderDynamoDbMapper {

    public OrderDynamoDbEntity toEntity(Order order) {
        OrderDynamoDbEntity entity = new OrderDynamoDbEntity();
        entity.setOrderId(order.orderId().value());
        entity.setEventId(order.eventId().value());
        entity.setUserId(order.userId());
        entity.setTicketIds(order.ticketIds());
        entity.setQuantity(order.quantity());
        entity.setTotalAmount(order.totalAmount().amount());
        entity.setCurrency(order.totalAmount().currency());
        entity.setStatus(order.status().name());
        entity.setFailureReason(order.failureReason());
        entity.setCreatedAt(order.createdAt().toString());
        entity.setUpdatedAt(order.updatedAt().toString());
        entity.setVersion(order.version());
        return entity;
    }

    public Order toDomain(OrderDynamoDbEntity entity) {
        return new Order(
                OrderId.of(entity.getOrderId()),
                EventId.of(entity.getEventId()),
                entity.getUserId(),
                entity.getTicketIds(),
                entity.getQuantity(),
                Money.of(entity.getTotalAmount(), entity.getCurrency()),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getFailureReason(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt()),
                entity.getVersion() != null ? entity.getVersion() : 0L
        );
    }
}
