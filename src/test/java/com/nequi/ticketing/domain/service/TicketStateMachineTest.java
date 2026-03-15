package com.nequi.ticketing.domain.service;

import com.nequi.ticketing.domain.exception.InvalidTicketStateException;
import com.nequi.ticketing.domain.model.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TicketStateMachine")
class TicketStateMachineTest {

    private TicketStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new TicketStateMachine();
    }

    @ParameterizedTest(name = "{0} → {1} should be valid")
    @CsvSource({
            "AVAILABLE, RESERVED",
            "AVAILABLE, COMPLIMENTARY",
            "RESERVED, AVAILABLE",
            "RESERVED, PENDING_CONFIRMATION",
            "PENDING_CONFIRMATION, SOLD",
            "PENDING_CONFIRMATION, AVAILABLE"
    })
    @DisplayName("should allow legal transitions")
    void shouldAllowLegalTransitions(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.isValidTransition(from, to)).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} should be invalid")
    @CsvSource({
            "AVAILABLE, SOLD",
            "AVAILABLE, PENDING_CONFIRMATION",
            "RESERVED, SOLD",
            "RESERVED, COMPLIMENTARY",
            "SOLD, AVAILABLE",
            "SOLD, RESERVED",
            "COMPLIMENTARY, AVAILABLE",
            "COMPLIMENTARY, SOLD"
    })
    @DisplayName("should reject illegal transitions")
    void shouldRejectIllegalTransitions(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.isValidTransition(from, to)).isFalse();
    }

    @Test
    @DisplayName("should throw InvalidTicketStateException on illegal transition")
    void shouldThrowOnIllegalTransition() {
        assertThatThrownBy(() ->
                stateMachine.validateTransition(TicketStatus.SOLD, TicketStatus.AVAILABLE))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("SOLD")
                .hasMessageContaining("AVAILABLE");
    }

    @Test
    @DisplayName("SOLD should be a final state with no valid transitions")
    void soldShouldBeFinal() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(stateMachine.isValidTransition(TicketStatus.SOLD, status)).isFalse();
        }
    }

    @Test
    @DisplayName("COMPLIMENTARY should be a final state with no valid transitions")
    void complimentaryShouldBeFinal() {
        for (TicketStatus status : TicketStatus.values()) {
            assertThat(stateMachine.isValidTransition(TicketStatus.COMPLIMENTARY, status)).isFalse();
        }
    }
}
