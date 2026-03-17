package com.nequi.shared.domain.port;

import com.nequi.shared.domain.model.OutboxMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OutboxRepository {

    Mono<OutboxMessage> save(OutboxMessage message);

    /** Returns unpublished messages for the OutboxPoller to process. */
    Flux<OutboxMessage> findUnpublished();

    Mono<Void> markPublished(String messageId);

    Mono<Void> delete(String messageId);
}