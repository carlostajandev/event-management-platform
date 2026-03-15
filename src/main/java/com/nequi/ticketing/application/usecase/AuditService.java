package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.out.AuditRepository;
import com.nequi.ticketing.domain.model.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service for persisting audit trail entries.
 *
 * <p>This service is designed to be called as a side effect — it never
 * blocks or fails the main flow. Audit failures are logged as warnings
 * but do NOT roll back the business operation.
 *
 * <p>Usage pattern:
 * <pre>
 * return mainOperation()
 *     .flatMap(result ->
 *         auditService.record(entry).thenReturn(result));
 * </pre>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Records an audit entry. Failures are silently absorbed — audit
     * must never break the business flow.
     */
    public Mono<Void> record(AuditEntry entry) {
        return auditRepository.save(entry)
                .doOnSuccess(v -> log.debug(
                        "Audit recorded: entity={} action={} correlationId={}",
                        entry.entityId(), entry.action(), entry.correlationId()))
                .onErrorResume(ex -> {
                    log.warn("Audit persistence failed for entity={} action={}: {}",
                            entry.entityId(), entry.action(), ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Convenience method for recording ticket state transitions.
     */
    public Mono<Void> recordTicketTransition(
            String ticketId,
            String userId,
            String correlationId,
            String from,
            String to) {
        return record(AuditEntry.of(
                ticketId,
                AuditEntry.EntityType.TICKET,
                AuditEntry.Action.valueOf(to),
                userId,
                correlationId,
                from,
                to
        ));
    }

    /**
     * Convenience method for recording order state transitions.
     */
    public Mono<Void> recordOrderTransition(
            String orderId,
            String userId,
            String correlationId,
            String from,
            String to) {
        return record(AuditEntry.of(
                orderId,
                AuditEntry.EntityType.ORDER,
                AuditEntry.Action.valueOf(to),
                userId,
                correlationId,
                from,
                to
        ));
    }
}
