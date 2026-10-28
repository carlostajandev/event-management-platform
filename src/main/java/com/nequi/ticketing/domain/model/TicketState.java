package com.nequi.ticketing.domain.model;

/**
 * Ticket states from the spec. A ticket is in exactly one state at a time.
 *
 * <pre>
 * AVAILABLE -> RESERVED -> PENDING_CONFIRMATION -> SOLD
 *     ^           |               |
 *     +-----------+---------------+   (hold expired)
 * AVAILABLE -> COMPLIMENTARY
 * </pre>
 */
public enum TicketState {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public boolean canTransitionTo(TicketState target) {
        return switch (this) {
            case AVAILABLE -> target == RESERVED || target == COMPLIMENTARY;
            case RESERVED -> target == PENDING_CONFIRMATION || target == AVAILABLE;
            case PENDING_CONFIRMATION -> target == SOLD || target == AVAILABLE;
            case SOLD, COMPLIMENTARY -> false;
        };
    }

    /** SOLD and COMPLIMENTARY are final. */
    public boolean isTerminal() {
        return this == SOLD || this == COMPLIMENTARY;
    }

    /** States that keep tickets out of the available pool until a deadline. */
    public boolean isHold() {
        return this == RESERVED || this == PENDING_CONFIRMATION;
    }

    /** Only SOLD counts as revenue; COMPLIMENTARY is final but not accountable. */
    public boolean isSale() {
        return this == SOLD;
    }
}
