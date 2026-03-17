package com.nequi.eventservice.application.port.in;

import com.nequi.eventservice.application.dto.AvailabilityResponse;
import reactor.core.publisher.Mono;

/**
 * Input port — queries the current ticket availability for a given event.
 *
 * <p>Reads the {@code availableCount} field from DynamoDB. For high-traffic events
 * the reservation-service uses this to pre-check availability before executing
 * the atomic conditional decrement.
 */
public interface GetAvailabilityUseCase {

    /**
     * Returns availability information for the event identified by {@code eventId}.
     *
     * @param eventId UUID string identifying the event
     * @return {@link Mono} emitting {@link AvailabilityResponse}, or
     *         {@link Mono#error} with {@link com.nequi.shared.domain.exception.EventNotFoundException}
     */
    Mono<AvailabilityResponse> execute(String eventId);
}