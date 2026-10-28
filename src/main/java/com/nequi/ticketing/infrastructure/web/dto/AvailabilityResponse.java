package com.nequi.ticketing.infrastructure.web.dto;

import com.nequi.ticketing.domain.model.Inventory;

/** RF7: what can still be bought, and what is held temporarily or already sold. */
public record AvailabilityResponse(
        String eventId,
        int total,
        int available,
        int reserved,
        int pendingConfirmation,
        int sold,
        int complimentary) {

    public static AvailabilityResponse from(Inventory inventory) {
        return new AvailabilityResponse(inventory.eventId().value(), inventory.total(), inventory.available(),
                inventory.reserved(), inventory.pendingConfirmation(), inventory.sold(), inventory.complimentary());
    }
}
