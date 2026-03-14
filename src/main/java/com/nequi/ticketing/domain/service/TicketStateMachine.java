package com.nequi.ticketing.domain.service;

import com.nequi.ticketing.domain.exception.InvalidTicketStateException;
import com.nequi.ticketing.domain.model.TicketStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Domain service that validates legal ticket state transitions.
 *
 * <p>Encapsulates all transition rules in one place.
 * Any code that changes ticket status MUST go through this service.
 *
 * <p>Legal transitions:
 * <pre>
 * AVAILABLE            → RESERVED, COMPLIMENTARY
 * RESERVED             → AVAILABLE (expired), PENDING_CONFIRMATION
 * PENDING_CONFIRMATION → SOLD, AVAILABLE (payment failed)
 * SOLD                 → (none — final state)
 * COMPLIMENTARY        → (none — final state)
 * </pre>
 */
@Component
public class TicketStateMachine {

    private static final Map<TicketStatus, Set<TicketStatus>> LEGAL_TRANSITIONS = Map.of(
            TicketStatus.AVAILABLE,             Set.of(TicketStatus.RESERVED, TicketStatus.COMPLIMENTARY),
            TicketStatus.RESERVED,              Set.of(TicketStatus.AVAILABLE, TicketStatus.PENDING_CONFIRMATION),
            TicketStatus.PENDING_CONFIRMATION,  Set.of(TicketStatus.SOLD, TicketStatus.AVAILABLE),
            TicketStatus.SOLD,                  Set.of(),
            TicketStatus.COMPLIMENTARY,         Set.of()
    );

    /**
     * Validates that the transition from {@code current} to {@code target} is legal.
     *
     * @param current current ticket status
     * @param target  desired target status
     * @throws InvalidTicketStateException if the transition is not allowed
     */
    public void validateTransition(TicketStatus current, TicketStatus target) {
        Set<TicketStatus> allowed = LEGAL_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new InvalidTicketStateException(current, target);
        }
    }

    /**
     * Returns true if the transition is valid without throwing.
     */
    public boolean isValidTransition(TicketStatus current, TicketStatus target) {
        return LEGAL_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }
}
