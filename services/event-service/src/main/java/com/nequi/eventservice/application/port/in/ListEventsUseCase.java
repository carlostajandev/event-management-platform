package com.nequi.eventservice.application.port.in;

import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.shared.domain.model.EventStatus;
import reactor.core.publisher.Flux;

/**
 * Input port — lists all events filtered by status.
 *
 * <p>Uses a DynamoDB GSI (GSI1PK = "STATUS#&lt;status&gt;") to efficiently
 * query events by status without a full table scan.
 */
public interface ListEventsUseCase {

    /**
     * Returns all events with the given {@code status}.
     *
     * @param status the {@link EventStatus} to filter by (ACTIVE, CANCELLED, SOLD_OUT, COMPLETED)
     * @return {@link Flux} streaming matching {@link EventResponse} items
     */
    Flux<EventResponse> execute(EventStatus status);
}