package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.HoldExpiredException;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;

import java.time.Duration;
import java.time.Instant;

/**
 * A purchase of {@code quantity} tickets of one event. All its tickets share the order status.
 *
 * <p>A hold has a single deadline, {@code expiresAt}, set when the order is placed. It covers both
 * RESERVED and PENDING_CONFIRMATION, so tickets are never held longer than {@link #HOLD_DURATION}.
 * Complimentary orders never hold tickets and have no deadline.
 */
public record Order(
        OrderId id,
        EventId eventId,
        UserId userId,
        int quantity,
        Money totalAmount,
        TicketState status,
        Instant createdAt,
        Instant expiresAt,
        Instant updatedAt) {

    public static final int MAX_TICKETS_PER_ORDER = 6;
    public static final Duration HOLD_DURATION = Duration.ofMinutes(10);

    public Order {
        if (id == null || eventId == null || userId == null || totalAmount == null || status == null
                || createdAt == null || updatedAt == null) {
            throw new DomainValidationException("Order is missing required fields");
        }
        if (quantity < 1) {
            throw new DomainValidationException("Order quantity must be positive");
        }
        if (status != TicketState.COMPLIMENTARY && expiresAt == null) {
            throw new DomainValidationException("A purchase order requires a hold deadline");
        }
    }

    /** Starts a purchase: tickets move AVAILABLE -> RESERVED until {@link #HOLD_DURATION} from now. */
    public static StateTransition place(OrderId id, Event event, UserId userId, int quantity, Instant now) {
        if (quantity < 1 || quantity > MAX_TICKETS_PER_ORDER) {
            throw new DomainValidationException("Quantity must be between 1 and %d".formatted(MAX_TICKETS_PER_ORDER));
        }
        requireUpcoming(event, now);
        Order order = new Order(id, event.id(), userId, quantity, event.ticketPrice().times(quantity),
                TicketState.RESERVED, now, now.plus(HOLD_DURATION), now);
        return StateTransition.of(order, TicketState.AVAILABLE, now);
    }

    /** Issues courtesy tickets: AVAILABLE -> COMPLIMENTARY, final and not counted as revenue. */
    public static StateTransition complimentary(OrderId id, Event event, UserId recipient, int quantity, Instant now) {
        if (quantity < 1) {
            throw new DomainValidationException("Quantity must be positive");
        }
        requireUpcoming(event, now);
        Order order = new Order(id, event.id(), recipient, quantity, event.ticketPrice().times(0),
                TicketState.COMPLIMENTARY, now, null, now);
        return StateTransition.of(order, TicketState.AVAILABLE, now);
    }

    /** The consumer accepted the order and payment can start: RESERVED -> PENDING_CONFIRMATION. */
    public StateTransition startConfirmation(Instant now) {
        return holdTransition(TicketState.RESERVED, TicketState.PENDING_CONFIRMATION, now);
    }

    /** Payment confirmed: PENDING_CONFIRMATION -> SOLD. */
    public StateTransition confirmSale(Instant now) {
        return holdTransition(TicketState.PENDING_CONFIRMATION, TicketState.SOLD, now);
    }

    /** Deadline passed without a sale: the held tickets go back to AVAILABLE. */
    public StateTransition release(Instant now) {
        if (!status.isHold()) {
            throw new IllegalTransitionException(status, TicketState.AVAILABLE);
        }
        if (!isExpired(now)) {
            throw new DomainValidationException("Order %s is held until %s".formatted(id.value(), expiresAt));
        }
        return transitionTo(TicketState.AVAILABLE, now);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    private StateTransition holdTransition(TicketState expected, TicketState target, Instant now) {
        if (status != expected) {
            throw new IllegalTransitionException(status, target);
        }
        if (isExpired(now)) {
            throw new HoldExpiredException(id, expiresAt);
        }
        return transitionTo(target, now);
    }

    private StateTransition transitionTo(TicketState target, Instant now) {
        Order next = new Order(id, eventId, userId, quantity, totalAmount, target, createdAt, expiresAt, now);
        return StateTransition.of(next, status, now);
    }

    private static void requireUpcoming(Event event, Instant now) {
        if (event.hasHappened(now)) {
            throw new DomainValidationException("Event %s has already taken place".formatted(event.id().value()));
        }
    }
}
