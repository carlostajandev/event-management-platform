package com.nequi.ticketing.infrastructure.web.dto;

import com.nequi.ticketing.domain.model.Event;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ComplimentaryRequest(
        @NotBlank @Size(max = 64) String recipientUserId,
        @Min(1) @Max(Event.MAX_CAPACITY) int quantity) {
}
