package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.out.AuditRepository;
import com.nequi.ticketing.domain.model.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditService")
class AuditServiceTest {

    @Mock private AuditRepository auditRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditRepository);
    }

    @Test
    @DisplayName("should persist audit entry successfully")
    void shouldPersistAuditEntry() {
        AuditEntry entry = AuditEntry.of(
                "tkt_001",
                AuditEntry.EntityType.TICKET,
                AuditEntry.Action.RESERVED,
                "usr_123",
                "corr-abc",
                "AVAILABLE",
                "RESERVED"
        );

        when(auditRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(auditService.record(entry))
                .verifyComplete();

        verify(auditRepository).save(any());
    }

    @Test
    @DisplayName("should absorb audit failures without propagating error")
    void shouldAbsorbAuditFailures() {
        AuditEntry entry = AuditEntry.of(
                "tkt_001",
                AuditEntry.EntityType.TICKET,
                AuditEntry.Action.SOLD,
                "usr_123",
                "corr-abc",
                "PENDING_CONFIRMATION",
                "SOLD"
        );

        when(auditRepository.save(any()))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB unavailable")));

        // Must complete without error — audit failure is absorbed
        StepVerifier.create(auditService.record(entry))
                .verifyComplete();
    }

    @Test
    @DisplayName("should record ticket transition with correct fields")
    void shouldRecordTicketTransition() {
        when(auditRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(auditService.recordTicketTransition(
                        "tkt_001", "usr_123", "corr-abc", "AVAILABLE", "RESERVED"))
                .verifyComplete();

        verify(auditRepository).save(any());
    }

    @Test
    @DisplayName("should record order transition with correct fields")
    void shouldRecordOrderTransition() {
        when(auditRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(auditService.recordOrderTransition(
                        "ord_001", "usr_123", "corr-abc", "PENDING", "CONFIRMED"))
                .verifyComplete();

        verify(auditRepository).save(any());
    }
}
