package com.nequi.ticketing.domain.model;

import java.time.Instant;

/**
 * Stored outcome of an idempotent request: the hash of the request body and the id of the resource
 * it created. A retry with the same key and hash returns the same resource; a different hash is rejected.
 */
public record IdempotencyRecord(IdempotencyKey key, String requestHash, String resourceId, Instant createdAt) {

    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }
}
