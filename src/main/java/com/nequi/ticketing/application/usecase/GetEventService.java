package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.application.port.in.GetEventUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link GetEventUseCase}.
 */
@Service
public class GetEventService implements GetEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetEventService.class);

    private final EventRepository eventRepository;

    public GetEventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public Mono<EventResponse> findById(String eventId) {
        log.debug("Finding event: eventId={}", eventId);

        return eventRepository.findById(EventId.of(eventId))
                .switchIfEmpty(Mono.error(new EventNotFoundException(EventId.of(eventId))))
                .map(EventResponse::from);
    }

    @Override
    public Flux<EventResponse> findAll() {
        log.debug("Fetching all events");

        return eventRepository.findAll()
                .map(EventResponse::from);
    }
}
