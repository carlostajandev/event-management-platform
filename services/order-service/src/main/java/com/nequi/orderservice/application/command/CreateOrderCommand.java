package com.nequi.orderservice.application.command;

/**
 * Immutable command for the create-order use case.
 *
 * <p>Combines the HTTP request body and the idempotency key header into a single
 * command object, so the use case has a clean single-argument contract.
 */
public record CreateOrderCommand(
        String reservationId,
        String userId,
        String idempotencyKey
) {}
