package com.nequi.shared.domain.model;

import java.time.Instant;

/**
 * Audit trail entry — records every state transition for compliance and debugging.
 *
 * <p>TTL: 90 days. Stored in emp-audit table:
 * <pre>
 *   PK: AUDIT#entity_id
 *   SK: TIMESTAMP#iso-timestamp
 * </pre>
 *
 * <p>AuditService is integrated in reservation-service (on ACTIVE→EXPIRED/CONFIRMED/CANCELLED)
 * and consumer-service (on PENDING_CONFIRMATION→CONFIRMED/FAILED).
 */
public record AuditEntry(
        String entityId,
        String entityType,      // "RESERVATION", "ORDER"
        String fromStatus,
        String toStatus,
        String userId,
        String correlationId,
        Instant timestamp,
        long ttl                // epoch seconds (now + 90 days)
) {
    /**
     * Factory — caller supplies {@code now} from an injected {@link java.time.Clock}
     * so that time-sensitive tests remain deterministic.
     */
    public static AuditEntry create(
            String entityId, String entityType,
            String fromStatus, String toStatus,
            String userId, String correlationId,
            Instant now) {
        return new AuditEntry(
                entityId, entityType, fromStatus, toStatus, userId, correlationId,
                now,
                now.plusSeconds(7_776_000L).getEpochSecond() // 90 days
        );
    }
}