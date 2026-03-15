package com.nequi.ticketing.application.port.in;

import com.nequi.ticketing.application.dto.AvailabilityResponse;
import reactor.core.publisher.Mono;

/**
 * Driving port for real-time ticket availability queries.
 */
public interface GetAvailabilityUseCase {

    /**
     * Returns current ticket availability for an event.
     * Considers both sold and temporarily reserved tickets.
     *
     * @param eventId the event identifier
     * @return current availability snapshot
     */
    Mono<AvailabilityResponse> getAvailability(String eventId);
}
