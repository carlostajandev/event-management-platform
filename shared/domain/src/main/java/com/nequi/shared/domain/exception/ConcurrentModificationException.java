package com.nequi.shared.domain.exception;

/**
 * Thrown when a DynamoDB conditional write fails due to version mismatch.
 * Upstream use cases catch this and apply exponential backoff retry (max 3 attempts),
 * then return 409 Conflict to the client.
 */
public class ConcurrentModificationException extends ConflictException {

    public ConcurrentModificationException(String entityId) {
        super("CONCURRENT_MODIFICATION",
                "Concurrent modification detected for entity: " + entityId + ". Retry after backoff.");
    }
}
