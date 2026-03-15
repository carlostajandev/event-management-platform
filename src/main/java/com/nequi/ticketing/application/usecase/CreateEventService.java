package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.CreateEventRequest;
import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.application.port.in.CreateEventUseCase;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.Venue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link CreateEventUseCase}.
 *
 * <p>Orchestrates the creation of a new event:
 * <ol>
 *   <li>Maps the incoming DTO to domain value objects</li>
 *   <li>Delegates creation to the domain factory method {@link Event#create}</li>
 *   <li>Persists via the repository port</li>
 *   <li>Maps the result back to a response DTO</li>
 * </ol>
 *
 * <p>This class depends on the domain and the repository port interface —
 * never on DynamoDB or any infrastructure class directly.
 */
@Service
public class CreateEventService implements CreateEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateEventService.class);

    private final EventRepository eventRepository;

    public CreateEventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public Mono<EventResponse> execute(CreateEventRequest request) {
        log.debug("Creating event: name={}, venue={}, capacity={}",
                request.name(), request.venueName(), request.totalCapacity());

        return Mono.fromCallable(() -> buildEvent(request))
                .flatMap(eventRepository::save)
                .map(EventResponse::from)
                .doOnSuccess(response ->
                        log.info("Event created successfully: eventId={}, name={}",
                                response.eventId(), response.name()));
    }

    private Event buildEvent(CreateEventRequest request) {
        Venue venue = Venue.of(
                request.venueName(),
                request.venueCity(),
                request.venueCountry()
        );
        Money price = Money.of(request.ticketPrice(), request.currency());

        return Event.create(
                request.name(),
                request.description(),
                request.eventDate(),
                venue,
                request.totalCapacity(),
                price
        );
    }
}
