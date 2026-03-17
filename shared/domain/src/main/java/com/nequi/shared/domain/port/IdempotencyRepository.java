package com.nequi.shared.domain.port;

import com.nequi.shared.domain.model.IdempotencyRecord;
import reactor.core.publisher.Mono;

public interface IdempotencyRepository {

    /** Returns existing record if the key was already processed. */
    Mono<IdempotencyRecord> findByKey(String key);

    Mono<IdempotencyRecord> save(IdempotencyRecord record);
}