package com.nequi.ticketing.domain.model;

import java.time.Instant;

/**
 * Immutable audit record for domain entity changes.
 *
 * <p>Records who changed what, when, and what the change was.
 * Stored in the {@code emp-audit} DynamoDB table with
 * composite key (entityId + timestamp).
 *
 * @param entityId    ID of the affected entity (orderId, ticketId, eventId)
 * @param entityType  Type of entity (ORDER, TICKET, EVENT)
 * @param action      What happened (CREATED, RESERVED, SOLD, RELEASED, FAILED)
 * @param userId      Who triggered the change (null for system actions)
 * @param correlationId  Request correlation ID for tracing
 * @param previousStatus Status before the change
 * @param newStatus      Status after the change
 * @param timestamp   When the change occurred
 * @param metadata    Additional context (JSON string, optional)
 */
public record AuditEntry(
        String entityId,
        String entityType,
        String action,
        String userId,
        String correlationId,
        String previousStatus,
        String newStatus,
        Instant timestamp,
        String metadata
) {

    public enum EntityType {
        ORDER, TICKET, EVENT
    }

    public enum Action {
        CREATED, RESERVED, PENDING_CONFIRMATION, SOLD,
        RELEASED, CONFIRMED, FAILED, CANCELLED
    }

    public static AuditEntry of(
            String entityId,
            EntityType entityType,
            Action action,
            String userId,
            String correlationId,
            String previousStatus,
            String newStatus) {
        return new AuditEntry(
                entityId,
                entityType.name(),
                action.name(),
                userId,
                correlationId,
                previousStatus,
                newStatus,
                Instant.now(),
                null
        );
    }
}
