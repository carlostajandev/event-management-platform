package com.nequi.ticketing.infrastructure.persistence.dynamodb.mapper;

import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.TicketId;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.TicketDynamoDbEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TicketDynamoDbMapper {

    public TicketDynamoDbEntity toEntity(Ticket ticket) {
        TicketDynamoDbEntity entity = new TicketDynamoDbEntity();
        entity.setTicketId(ticket.ticketId().value());
        entity.setEventId(ticket.eventId().value());
        entity.setUserId(ticket.userId());
        entity.setOrderId(ticket.orderId());
        entity.setStatus(ticket.status().name());
        entity.setPrice(ticket.price().amount());
        entity.setCurrency(ticket.price().currency());
        entity.setReservedAt(ticket.reservedAt() != null ? ticket.reservedAt().toString() : null);
        entity.setExpiresAt(ticket.expiresAt() != null ? ticket.expiresAt().toString() : null);
        entity.setConfirmedAt(ticket.confirmedAt() != null ? ticket.confirmedAt().toString() : null);
        entity.setCreatedAt(ticket.createdAt().toString());
        entity.setUpdatedAt(ticket.updatedAt().toString());
        entity.setVersion(ticket.version());
        return entity;
    }

    public Ticket toDomain(TicketDynamoDbEntity entity) {
        return new Ticket(
                TicketId.of(entity.getTicketId()),
                EventId.of(entity.getEventId()),
                entity.getUserId(),
                entity.getOrderId(),
                TicketStatus.valueOf(entity.getStatus()),
                Money.of(entity.getPrice(), entity.getCurrency()),
                parseInstant(entity.getReservedAt()),
                parseInstant(entity.getExpiresAt()),
                parseInstant(entity.getConfirmedAt()),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt()),
                entity.getVersion() != null ? entity.getVersion() : 0L
        );
    }

    private Instant parseInstant(String value) {
        return value != null ? Instant.parse(value) : null;
    }
}
