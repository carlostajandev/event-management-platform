package com.nequi.ticketing.infrastructure.persistence.dynamodb.mapper;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.Venue;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.EventDynamoDbEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Maps between the domain {@link Event} and the DynamoDB persistence entity.
 *
 * <p>This mapper lives in infrastructure — the domain model has no knowledge
 * of DynamoDB types or annotations.
 */
@Component
public class EventDynamoDbMapper {

    public EventDynamoDbEntity toEntity(Event event) {
        EventDynamoDbEntity entity = new EventDynamoDbEntity();
        entity.setEventId(event.eventId().value());
        entity.setName(event.name());
        entity.setDescription(event.description());
        entity.setEventDate(event.eventDate().toString());
        entity.setVenueName(event.venue().name());
        entity.setVenueCity(event.venue().city());
        entity.setVenueCountry(event.venue().country());
        entity.setTotalCapacity(event.totalCapacity());
        entity.setAvailableTickets(event.availableTickets());
        entity.setTicketPrice(event.ticketPrice().amount());
        entity.setCurrency(event.ticketPrice().currency());
        entity.setStatus(event.status().name());
        entity.setCreatedAt(event.createdAt().toString());
        entity.setUpdatedAt(event.updatedAt().toString());
        entity.setVersion(event.version());
        return entity;
    }

    public Event toDomain(EventDynamoDbEntity entity) {
        return new Event(
                EventId.of(entity.getEventId()),
                entity.getName(),
                entity.getDescription(),
                Instant.parse(entity.getEventDate()),
                Venue.of(entity.getVenueName(), entity.getVenueCity(), entity.getVenueCountry()),
                entity.getTotalCapacity(),
                entity.getAvailableTickets(),
                Money.of(entity.getTicketPrice(), entity.getCurrency()),
                Event.EventStatus.valueOf(entity.getStatus()),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt()),
                entity.getVersion() != null ? entity.getVersion() : 0L
        );
    }
}
