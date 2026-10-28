package com.nequi.ticketing.infrastructure.persistence.dynamodb;

/** Transaction kept colliding with concurrent writes on the same items after all retries. Safe to retry later. */
public class TransactionContentionException extends RuntimeException {

    TransactionContentionException(Throwable cause) {
        super("Too many concurrent writes on the same items, retry later", cause);
    }
}
