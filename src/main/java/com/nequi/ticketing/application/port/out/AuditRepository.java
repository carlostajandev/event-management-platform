package com.nequi.ticketing.application.port.out;

import com.nequi.ticketing.domain.model.AuditEntry;
import reactor.core.publisher.Mono;

/**
 * Driven port for persisting audit trail entries.
 */
public interface AuditRepository {

    /**
     * Persists an audit entry. Fire-and-forget — failures are logged
     * but never propagate to the calling use case.
     *
     * @param entry the audit entry to persist
     * @return empty Mono when saved
     */
    Mono<Void> save(AuditEntry entry);
}
