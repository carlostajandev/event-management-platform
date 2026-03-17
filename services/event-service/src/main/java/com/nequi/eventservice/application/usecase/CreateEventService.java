package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.EventConstants;
import com.nequi.eventservice.application.command.CreateEventCommand;
import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.mapper.EventMapper;
import com.nequi.eventservice.application.port.in.CreateEventUseCase;
import com.nequi.shared.domain.port.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Application service that implements the create-event use case.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Generates a UUID and timestamps via the injected {@link Clock}</li>
 *   <li>Delegates domain construction to {@link EventMapper#toDomain}</li>
 *   <li>Persists via {@link EventRepository} (DynamoDB)</li>
 *   <li>Maps the persisted entity back to {@link EventResponse} via {@link EventMapper#toResponse}</li>
 *   <li>Increments the {@code events.created.total} Micrometer counter</li>
 *   <li>Logs structured MDC fields for CloudWatch correlation</li>
 * </ul>
 *
 * <p>No domain logic lives here — validation happens at the HTTP layer,
 * business invariants live in the domain model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateEventService implements CreateEventUseCase {

    private final EventRepository eventRepository;
    private final EventMapper     eventMapper;
    private final MeterRegistry   meterRegistry;
    private final Clock           clock;

    @Override
    public Mono<EventResponse> execute(CreateEventCommand command) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now(clock);

        return eventRepository.save(eventMapper.toDomain(command, eventId, now))
                .map(eventMapper::toResponse)
                .doOnSuccess(response -> {
                    meterRegistry.counter(EventConstants.METRIC_EVENTS_CREATED).increment();
                    MDC.put("eventId", response.id());
                    MDC.put("eventName", response.name());
                    log.info("Event created successfully: eventId={}, name={}", response.id(), response.name());
                    MDC.remove("eventId");
                    MDC.remove("eventName");
                })
                .doOnError(error ->
                    log.error("Failed to create event: name={}, error={}", command.name(), error.getMessage(), error)
                );
    }
}
