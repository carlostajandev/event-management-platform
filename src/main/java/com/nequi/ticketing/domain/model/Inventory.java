package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;
import com.nequi.ticketing.domain.exception.InsufficientTicketsException;

/**
 * Ticket counters per state for one event.
 * Invariant: available + reserved + pendingConfirmation + sold + complimentary = total.
 */
public record Inventory(
        EventId eventId,
        int total,
        int available,
        int reserved,
        int pendingConfirmation,
        int sold,
        int complimentary) {

    public Inventory {
        if (eventId == null) {
            throw new DomainValidationException("Inventory requires an event id");
        }
        if (Math.min(Math.min(available, reserved), Math.min(Math.min(pendingConfirmation, sold), complimentary)) < 0) {
            throw new DomainValidationException("Inventory counters must not be negative");
        }
        if (available + reserved + pendingConfirmation + sold + complimentary != total) {
            throw new DomainValidationException("Inventory counters must add up to the total");
        }
    }

    public static Inventory initial(EventId eventId, int capacity) {
        return new Inventory(eventId, capacity, capacity, 0, 0, 0, 0);
    }

    public int count(TicketState state) {
        return switch (state) {
            case AVAILABLE -> available;
            case RESERVED -> reserved;
            case PENDING_CONFIRMATION -> pendingConfirmation;
            case SOLD -> sold;
            case COMPLIMENTARY -> complimentary;
        };
    }

    public Inventory apply(InventoryMove move) {
        if (!move.from().canTransitionTo(move.to())) {
            throw new IllegalTransitionException(move.from(), move.to());
        }
        int fromCount = count(move.from());
        if (fromCount < move.quantity()) {
            if (move.from() == TicketState.AVAILABLE) {
                throw new InsufficientTicketsException(eventId, move.quantity(), fromCount);
            }
            throw new DomainValidationException("Only %d tickets are %s".formatted(fromCount, move.from()));
        }
        return new Inventory(eventId, total,
                available + delta(TicketState.AVAILABLE, move),
                reserved + delta(TicketState.RESERVED, move),
                pendingConfirmation + delta(TicketState.PENDING_CONFIRMATION, move),
                sold + delta(TicketState.SOLD, move),
                complimentary + delta(TicketState.COMPLIMENTARY, move));
    }

    private static int delta(TicketState state, InventoryMove move) {
        return (state == move.to() ? move.quantity() : 0) - (state == move.from() ? move.quantity() : 0);
    }
}
