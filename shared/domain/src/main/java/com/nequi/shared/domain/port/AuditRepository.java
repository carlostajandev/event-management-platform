package com.nequi.shared.domain.port;

import com.nequi.shared.domain.model.AuditEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AuditRepository {

    Mono<AuditEntry> save(AuditEntry entry);

    Flux<AuditEntry> findByEntityId(String entityId);
}