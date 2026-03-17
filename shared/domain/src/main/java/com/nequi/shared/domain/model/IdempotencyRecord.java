package com.nequi.shared.domain.model;

import java.time.Instant;

/**
 * Cached idempotency response — prevents duplicate order creation on client retries.
 *
 * <p>Stored in emp-idempotency-keys:
 * <pre>PK: KEY#&lt;idempotency-uuid&gt;</pre>
 *
 * <p>TTL of 24 hours. On duplicate request with same key:
 * <ol>
 *   <li>Lookup by key — if found, return {@code cachedResponse} immediately (HTTP 200)</li>
 *   <li>If not found — process, then write order + outbox + idempotency atomically</li>
 * </ol>
 */
public record IdempotencyRecord(
        String key,
        String orderId,
        String cachedResponseJson,  // serialized OrderResponse
        Instant createdAt,
        long ttl                    // epoch seconds (now + 24h)
) {
    public static IdempotencyRecord create(String key, String orderId, String responseJson) {
        Instant now = Instant.now();
        return new IdempotencyRecord(
                key, orderId, responseJson, now,
                now.plusSeconds(86_400L).getEpochSecond()
        );
    }
}