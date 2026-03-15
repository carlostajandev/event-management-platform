package com.nequi.ticketing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * Input DTO for creating a purchase order.
 * This is the body received after tickets have been reserved.
 */
public record CreatePurchaseOrderCommand(

        @NotBlank(message = "orderId is required")
        String orderId,

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "userId is required")
        String userId,

        @NotEmpty(message = "ticketIds cannot be empty")
        List<String> ticketIds,

        @Positive(message = "totalAmount must be positive")
        BigDecimal totalAmount,

        @NotBlank(message = "currency is required")
        String currency
) {}
