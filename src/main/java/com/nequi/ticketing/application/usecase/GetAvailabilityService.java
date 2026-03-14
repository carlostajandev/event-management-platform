package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.AvailabilityResponse;
import com.nequi.ticketing.application.port.in.GetAvailabilityUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Returns real-time ticket availability for an event.
 *
 * <p>Queries the ticket repository by status using the DynamoDB GSI
 * (eventId-status-index) for efficient lookups without full table scans.
 *
 * <p>Availability = totalCapacity - reserved - sold
 * Both RESERVED and PENDING_CONFIRMATION tickets reduce availability
 * to prevent overselling during concurrent purchases.
 */
@Service
public class GetAvailabilityService implements GetAvailabilityUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetAvailabilityService.class);

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;

    public GetAvailabilityService(EventRepository eventRepository,
                                   TicketRepository ticketRepository) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
    }

    @Override
    public Mono<AvailabilityResponse> getAvailability(String eventId) {
        EventId id = EventId.of(eventId);
        log.debug("Getting availability for event: {}", eventId);

        return eventRepository.findById(id)
                .switchIfEmpty(Mono.error(new EventNotFoundException(id)))
                .flatMap(event -> {
                    Mono<Long> available = ticketRepository
                            .countByEventIdAndStatus(id, TicketStatus.AVAILABLE);
                    Mono<Long> reserved = ticketRepository
                            .countByEventIdAndStatus(id, TicketStatus.RESERVED);
                    Mono<Long> pending = ticketRepository
                            .countByEventIdAndStatus(id, TicketStatus.PENDING_CONFIRMATION);
                    Mono<Long> sold = ticketRepository
                            .countByEventIdAndStatus(id, TicketStatus.SOLD);

                    return Mono.zip(available, reserved, pending, sold)
                            .map(tuple -> new AvailabilityResponse(
                                    eventId,
                                    tuple.getT1(),
                                    tuple.getT2() + tuple.getT3(), // reserved + pending
                                    tuple.getT4(),
                                    event.totalCapacity(),
                                    tuple.getT1() > 0
                            ));
                });
    }
}
