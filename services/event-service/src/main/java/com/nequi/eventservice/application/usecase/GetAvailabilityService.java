package com.nequi.eventservice.application.usecase;

import com.nequi.eventservice.application.dto.AvailabilityResponse;
import com.nequi.eventservice.application.port.in.GetAvailabilityUseCase;
import com.nequi.shared.domain.exception.EventNotFoundException;
import com.nequi.shared.domain.port.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service that implements the get-availability use case.
 *
 * <p>Reads {@code availableCount} from DynamoDB and exposes it as a
 * Micrometer gauge tagged by {@code eventId}, enabling per-event availability
 * monitoring in Prometheus/Grafana dashboards.
 *
 * <p>The gauge is registered lazily on first query to avoid pre-creating
 * metrics for events that may never be queried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetAvailabilityService implements GetAvailabilityUseCase {

    private static final String GAUGE_AVAILABLE_TICKETS = "event.available_tickets";

    private final EventRepository eventRepository;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<AvailabilityResponse> execute(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(new EventNotFoundException(eventId)))
                .map(event -> {
                    int availableCount = event.availableCount();

                    // Register / update gauge: last-value wins, tagged per event
                    meterRegistry.gauge(
                            GAUGE_AVAILABLE_TICKETS,
                            Tags.of("eventId", eventId),
                            availableCount
                    );

                    log.debug("Availability queried: eventId={}, availableCount={}", eventId, availableCount);
                    return new AvailabilityResponse(eventId, availableCount, availableCount > 0);
                })
                .doOnError(EventNotFoundException.class, ex ->
                    log.warn("Availability check for unknown event: eventId={}", eventId)
                )
                .doOnError(error -> {
                    if (!(error instanceof EventNotFoundException)) {
                        log.error("Failed to retrieve availability: eventId={}, error={}", eventId, error.getMessage(), error);
                    }
                });
    }
}