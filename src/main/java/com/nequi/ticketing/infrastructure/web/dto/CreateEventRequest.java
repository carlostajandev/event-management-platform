package com.nequi.ticketing.infrastructure.web.dto;

import com.nequi.ticketing.domain.model.Event;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Future Instant date,
        @Min(1) @Max(Event.MAX_CAPACITY) int capacity,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal ticketPrice,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
}
