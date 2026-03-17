package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.mapper.EventMapper;
import com.nequi.eventservice.application.port.in.GetEventUseCase;
import com.nequi.shared.domain.exception.EventNotFoundException;
import com.nequi.shared.domain.port.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service that implements the get-event use case.
 *
 * <p>Delegates to {@link EventRepository#findById(String)}, switching to an
 * {@link EventNotFoundException} when the repository emits an empty signal,
 * which the global error handler maps to HTTP 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetEventService implements GetEventUseCase {

    private final EventRepository eventRepository;
    private final EventMapper     eventMapper;

    @Override
    public Mono<EventResponse> execute(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(new EventNotFoundException(eventId)))
                .map(eventMapper::toResponse)
                .doOnSuccess(response ->
                    log.debug("Event retrieved: eventId={}, status={}", response.id(), response.status())
                )
                .doOnError(EventNotFoundException.class, ex ->
                    log.warn("Event not found: eventId={}", eventId)
                );
    }
}
