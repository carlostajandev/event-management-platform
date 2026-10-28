package com.nequi.ticketing.infrastructure.web.dto;

import com.nequi.ticketing.domain.model.Order;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @NotBlank @Size(max = 64) String eventId,
        @Min(1) @Max(Order.MAX_TICKETS_PER_ORDER) int quantity) {
}
