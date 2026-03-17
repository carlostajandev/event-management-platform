package com.nequi.eventservice.application.mapper;

import com.nequi.eventservice.application.command.CreateEventCommand;
import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.dto.VenueRequest;
import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Maps between {@link Event} domain model and event-service DTOs.
 *
 * <p>Rule: no use case maps inline. All conversions go through this class.
 * <ul>
 *   <li>{@link #toDomain} — inbound: command → domain model</li>
 *   <li>{@link #toResponse} — outbound: domain model → response DTO</li>
 * </ul>
 */
@Component
public class EventMapper {

    public Event toDomain(CreateEventCommand command, String eventId, Instant now) {
        return new Event(
                eventId,
                command.name(),
                command.description(),
                command.venue(),          // already a domain Venue — no conversion needed
                command.eventDate(),
                command.ticketPrice(),
                command.currency(),
                command.totalCapacity(),
                command.totalCapacity(),  // availableCount starts equal to totalCapacity
                0L,                       // initial version for optimistic locking
                EventStatus.ACTIVE,
                now,
                now
        );
    }

    public EventResponse toResponse(Event event) {
        VenueRequest venueResponse = new VenueRequest(
                event.venue().name(),
                event.venue().address(),
                event.venue().city(),
                event.venue().country(),
                event.venue().capacity()
        );
        return new EventResponse(
                event.id(),
                event.name(),
                event.description(),
                venueResponse,
                event.eventDate(),
                event.ticketPrice(),
                event.currency(),
                event.totalCapacity(),
                event.availableCount(),
                event.status(),
                event.createdAt(),
                event.updatedAt()
        );
    }
}
