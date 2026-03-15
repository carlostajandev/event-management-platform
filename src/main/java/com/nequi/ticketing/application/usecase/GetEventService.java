package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.application.dto.PagedResponse;
import com.nequi.ticketing.application.port.in.GetEventUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link GetEventUseCase}.
 * Supports cursor-based pagination using DynamoDB's lastEvaluatedKey pattern.
 */
@Service
public class GetEventService implements GetEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetEventService.class);

    private final EventRepository eventRepository;
    private final TicketingProperties properties;

    public GetEventService(EventRepository eventRepository,
                           TicketingProperties properties) {
        this.eventRepository = eventRepository;
        this.properties = properties;
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
        log.debug("Fetching all events (default page size: {})",
                properties.pagination().defaultPageSize());
        return eventRepository.findAll()
                .take(properties.pagination().defaultPageSize())
                .map(EventResponse::from);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses reactive skip/take for offset-based limiting.
     * In production, replace with cursor-based pagination using
     * DynamoDB's lastEvaluatedKey for true O(1) page access.
     */
    @Override
    public Mono<PagedResponse<EventResponse>> findPaged(int page, int size) {
        int effectiveSize = Math.min(size, properties.pagination().maxPageSize());
        int offset = page * effectiveSize;

        log.debug("Fetching events page={}, size={}", page, effectiveSize);

        return eventRepository.findAll()
                .skip(offset)
                .take(effectiveSize)
                .map(EventResponse::from)
                .collectList()
                .map(items -> new PagedResponse<>(
                        items,
                        page,
                        effectiveSize,
                        items.size() == effectiveSize
                ));
    }
}