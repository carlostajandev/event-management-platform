package com.nequi.ticketing.domain.model;

/**
 * Represents all possible states in the lifecycle of a ticket.
 *
 * <p>State transition rules (enforced by {@link TicketStateMachine}):
 * <pre>
 * AVAILABLE → RESERVED            (user starts purchase)
 * RESERVED  → AVAILABLE           (reservation expires after 10 min)
 * RESERVED  → PENDING_CONFIRMATION (payment initiated)
 * PENDING_CONFIRMATION → SOLD     (payment confirmed — FINAL)
 * PENDING_CONFIRMATION → AVAILABLE (payment failed — returns to pool)
 * AVAILABLE → COMPLIMENTARY       (admin grants complimentary — FINAL)
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>SOLD is final and irreversible.</li>
 *   <li>COMPLIMENTARY is final but not billable.</li>
 *   <li>RESERVED and PENDING_CONFIRMATION do not count as sales.</li>
 * </ul>
 */
public enum TicketStatus {

    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public boolean isFinal() {
        return this == SOLD || this == COMPLIMENTARY;
    }

    public boolean isSale() {
        return this == SOLD;
    }

    public boolean isAvailableForPurchase() {
        return this == AVAILABLE;
    }
}
