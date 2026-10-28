package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;
import com.nequi.ticketing.domain.exception.InsufficientTicketsException;
import org.junit.jupiter.api.Test;

import static com.nequi.ticketing.domain.model.TicketState.AVAILABLE;
import static com.nequi.ticketing.domain.model.TicketState.COMPLIMENTARY;
import static com.nequi.ticketing.domain.model.TicketState.PENDING_CONFIRMATION;
import static com.nequi.ticketing.domain.model.TicketState.RESERVED;
import static com.nequi.ticketing.domain.model.TicketState.SOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    private static final EventId EVENT_ID = new EventId("event-1");

    @Test
    void startsWithEverythingAvailable() {
        Inventory inventory = Inventory.initial(EVENT_ID, 50);

        assertThat(inventory.count(AVAILABLE)).isEqualTo(50);
        assertThat(inventory.count(RESERVED) + inventory.count(PENDING_CONFIRMATION)
                + inventory.count(SOLD) + inventory.count(COMPLIMENTARY)).isZero();
    }

    @Test
    void movesTicketsBetweenCountersKeepingTheTotal() {
        Inventory inventory = Inventory.initial(EVENT_ID, 10)
                .apply(new InventoryMove(AVAILABLE, RESERVED, 4))
                .apply(new InventoryMove(RESERVED, PENDING_CONFIRMATION, 3))
                .apply(new InventoryMove(PENDING_CONFIRMATION, SOLD, 2))
                .apply(new InventoryMove(PENDING_CONFIRMATION, AVAILABLE, 1))
                .apply(new InventoryMove(AVAILABLE, COMPLIMENTARY, 2));

        assertThat(inventory).isEqualTo(new Inventory(EVENT_ID, 10, 5, 1, 0, 2, 2));
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        Inventory inventory = Inventory.initial(EVENT_ID, 2);

        assertThatThrownBy(() -> inventory.apply(new InventoryMove(AVAILABLE, RESERVED, 3)))
                .isInstanceOf(InsufficientTicketsException.class)
                .hasMessageContaining("2 tickets available, 3 requested");
    }

    @Test
    void cannotMoveMoreThanTheSourceCounterHolds() {
        Inventory inventory = Inventory.initial(EVENT_ID, 5).apply(new InventoryMove(AVAILABLE, RESERVED, 1));

        assertThatThrownBy(() -> inventory.apply(new InventoryMove(RESERVED, PENDING_CONFIRMATION, 2)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsMovesOutsideTheStateMachine() {
        Inventory inventory = new Inventory(EVENT_ID, 3, 1, 0, 0, 2, 0);

        assertThatThrownBy(() -> inventory.apply(new InventoryMove(SOLD, AVAILABLE, 1)))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessage("Transition SOLD -> AVAILABLE is not allowed");
    }

    @Test
    void enforcesTheInvariant() {
        assertThatThrownBy(() -> new Inventory(EVENT_ID, 10, 9, 0, 0, 0, 0))
                .isInstanceOf(DomainValidationException.class).hasMessageContaining("add up");
        assertThatThrownBy(() -> new Inventory(EVENT_ID, 10, 11, -1, 0, 0, 0))
                .isInstanceOf(DomainValidationException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> new Inventory(null, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsInvalidMoves() {
        assertThatThrownBy(() -> new InventoryMove(AVAILABLE, RESERVED, 0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new InventoryMove(null, RESERVED, 1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new InventoryMove(AVAILABLE, null, 1)).isInstanceOf(DomainValidationException.class);
    }
}
