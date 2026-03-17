package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.mapper.EventMapper;
import com.nequi.eventservice.application.port.in.ListEventsUseCase;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.port.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Application service that implements the list-events use case.
 *
 * <p>Queries DynamoDB via the GSI1 index (GSI1PK = "STATUS#&lt;status&gt;"),
 * avoiding full table scans. Results are streamed reactively as a {@link Flux}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListEventsService implements ListEventsUseCase {

    private final EventRepository eventRepository;
    private final EventMapper     eventMapper;

    @Override
    public Flux<EventResponse> execute(EventStatus status) {
        return eventRepository.findByStatus(status)
                .map(eventMapper::toResponse)
                .doOnSubscribe(sub ->
                    log.debug("Listing events by status={}", status)
                )
                .doOnError(error ->
                    log.error("Failed to list events by status={}: {}", status, error.getMessage(), error)
                );
    }
}
