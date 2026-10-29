package com.nequi.ticketing.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.nequi.ticketing.domain.model.TicketState.AVAILABLE;
import static com.nequi.ticketing.domain.model.TicketState.COMPLIMENTARY;
import static com.nequi.ticketing.domain.model.TicketState.PENDING_CONFIRMATION;
import static com.nequi.ticketing.domain.model.TicketState.RESERVED;
import static com.nequi.ticketing.domain.model.TicketState.SOLD;
import static org.assertj.core.api.Assertions.assertThat;

class TicketStateTest {

    private static final Set<String> ALLOWED = Set.of(
            "AVAILABLE->RESERVED",
            "AVAILABLE->COMPLIMENTARY",
            "RESERVED->PENDING_CONFIRMATION",
            "RESERVED->AVAILABLE",
            "PENDING_CONFIRMATION->SOLD",
            "PENDING_CONFIRMATION->AVAILABLE");

    static Stream<Arguments> allPairs() {
        return Arrays.stream(TicketState.values())
                .flatMap(from -> Arrays.stream(TicketState.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void onlyTheSpecTransitionsAreAllowed(TicketState from, TicketState to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + "->" + to));
    }

    @ParameterizedTest
    @EnumSource(TicketState.class)
    void classifiesEachState(TicketState state) {
        assertThat(state.isTerminal()).isEqualTo(state == SOLD || state == COMPLIMENTARY);
        assertThat(state.isHold()).isEqualTo(state == RESERVED || state == PENDING_CONFIRMATION);
        assertThat(state.isSale()).isEqualTo(state == SOLD);
    }

    @ParameterizedTest
    @EnumSource(value = TicketState.class, names = {"SOLD", "COMPLIMENTARY"})
    void terminalStatesHaveNoWayOut(TicketState terminal) {
        assertThat(Arrays.stream(TicketState.values()).noneMatch(terminal::canTransitionTo)).isTrue();
        assertThat(AVAILABLE.canTransitionTo(PENDING_CONFIRMATION)).isFalse();
    }
}
