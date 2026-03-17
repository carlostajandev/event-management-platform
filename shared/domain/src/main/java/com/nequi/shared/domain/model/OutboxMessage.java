package com.nequi.shared.domain.model;

import java.time.Instant;

/**
 * Outbox message — guarantees at-least-once delivery to SQS even if SQS is down.
 *
 * <p>Written atomically with the Order in the same DynamoDB TransactWriteItems call.
 * The OutboxPoller (consumer-service) reads emp-outbox every 5 seconds, publishes
 * unprocessed messages to SQS, and marks them as published (or deletes them).
 *
 * <p>TTL of 24 hours: DynamoDB auto-deletes processed messages without explicit cleanup.
 * If published=false after 24h, the message is assumed stale and purged.
 */
public record OutboxMessage(
        String id,
        String aggregateId,     // orderId — for correlation
        String aggregateType,   // "ORDER" — for routing
        String eventType,       // "ORDER_PLACED", "RESERVATION_EXPIRED"
        String payload,         // JSON serialized message
        boolean published,
        Instant createdAt,
        long ttl                // epoch seconds (now + 24h)
) {
    public OutboxMessage markPublished() {
        return new OutboxMessage(id, aggregateId, aggregateType, eventType, payload,
                true, createdAt, ttl);
    }
}