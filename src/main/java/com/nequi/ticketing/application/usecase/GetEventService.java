package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.EventResponse;
import com.nequi.ticketing.application.dto.PagedResponse;
import com.nequi.ticketing.application.port.in.GetEventUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link GetEventUseCase}.
 *
 * <p>Clean Architecture fix: this service previously depended on
 * {@code TicketingProperties} from the {@code infrastructure.config} package,
 * violating the dependency rule (application layer must not import infrastructure).
 *
 * <p>Fix: configuration values are injected via {@code @Value} — Spring
 * annotations are permitted in the application layer (they are part of
 * the Spring application framework, not an infrastructure adapter).
 * The service no longer imports any infrastructure class.
 */
@Service
public class GetEventService implements GetEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetEventService.class);

    private final EventRepository eventRepository;
    private final int defaultPageSize;
    private final int maxPageSize;

    public GetEventService(
            EventRepository eventRepository,
            @Value("${ticketing.pagination.default-page-size:20}") int defaultPageSize,
            @Value("${ticketing.pagination.max-page-size:100}") int maxPageSize) {
        this.eventRepository = eventRepository;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
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
        log.debug("Fetching all events (default page size: {})", defaultPageSize);
        return eventRepository.findAll()
                .take(defaultPageSize)
                .map(EventResponse::from);
    }

    public Mono<PagedResponse<EventResponse>> findPaged(int page, int size) {
        int effectiveSize = Math.min(size, maxPageSize);
        int offset = page * effectiveSize;
        log.debug("Fetching events page={}, size={}", page, effectiveSize);

        return eventRepository.findAll()
                .skip(offset)
                .take(effectiveSize)
                .map(EventResponse::from)
                .collectList()
                .map(items -> new PagedResponse<>(items, page, effectiveSize,
                        items.size() == effectiveSize));
    }
}