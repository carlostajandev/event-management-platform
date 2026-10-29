package com.nequi.ticketing.domain.port;

import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import reactor.core.publisher.Mono;

/** Read side of idempotency. Records are written inside the transaction of the operation they protect. */
public interface IdempotencyRepository {

    Mono<IdempotencyRecord> find(IdempotencyKey key);
}
