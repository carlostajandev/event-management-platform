package com.nequi.ticketing.integration;

import com.nequi.ticketing.application.dto.AvailabilityResponse;
import com.nequi.ticketing.domain.model.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the availability endpoint.
 *
 * <p>Verifies that GET /api/v1/events/{eventId}/availability reflects
 * real-time ticket state changes — reservations reduce available count,
 * and the response is always consistent with the actual DynamoDB state.
 */
@DisplayName("Real-time Availability")
class AvailabilityIT extends IntegrationTestBase {

    @Test
    @DisplayName("GET /availability reflects full capacity for a new event")
    void shouldReturnFullCapacity_forNewEvent() {
        final int capacity = 100;
        final String eventId = setupEventWithTickets(capacity);

        AvailabilityResponse response = getAvailability(eventId);

        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.availableTickets()).isEqualTo(capacity);
        assertThat(response.reservedTickets()).isZero();
        assertThat(response.soldTickets()).isZero();
        assertThat(response.totalCapacity()).isEqualTo(capacity);
        assertThat(response.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("GET /availability reflects reservations immediately")
    void shouldReflectReservations_inRealTime() {
        final int capacity = 20;
        final int toReserve = 7;
        final String eventId = setupEventWithTickets(capacity);

        // Reserva 7 tickets
        for (int i = 0; i < toReserve; i++) {
            int status = reserveOneTicket(eventId, "usr_avail_" + i);
            assertThat(status).isEqualTo(201);
        }

        // La disponibilidad debe reflejar inmediatamente los 7 reservados
        AvailabilityResponse response = getAvailability(eventId);

        assertThat(response.availableTickets())
                .as("Disponibles debe ser capacidad - reservados")
                .isEqualTo(capacity - toReserve);

        assertThat(response.reservedTickets())
                .as("Reservados debe coincidir con los 7 reservados")
                .isEqualTo(toReserve);

        assertThat(response.soldTickets()).isZero();
        assertThat(response.totalCapacity()).isEqualTo(capacity);
        assertThat(response.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("GET /availability returns 404 for unknown event")
    void shouldReturn404_forUnknownEvent() {
        webTestClient.get()
                .uri("/api/v1/events/evt_nonexistent_99999/availability")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET /availability shows isAvailable=false when fully reserved")
    void shouldShowNotAvailable_whenFullyReserved() {
        final int capacity = 3;
        final String eventId = setupEventWithTickets(capacity);

        // Reserva todos los tickets
        for (int i = 0; i < capacity; i++) {
            reserveOneTicket(eventId, "usr_full_" + i);
        }

        AvailabilityResponse response = getAvailability(eventId);

        assertThat(response.availableTickets()).isZero();
        assertThat(response.reservedTickets()).isEqualTo(capacity);
        assertThat(response.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("GET /availability is consistent with DynamoDB state")
    void shouldBeConsistentWithDynamoDbState() {
        final int capacity = 10;
        final String eventId = setupEventWithTickets(capacity);

        // Reserva 4 tickets via API
        for (int i = 0; i < 4; i++) {
            reserveOneTicket(eventId, "usr_consistency_" + i);
        }

        // Consulta via API
        AvailabilityResponse apiResponse = getAvailability(eventId);

        // Consulta directamente DynamoDB via repositorio
        long dbAvailable = countTickets(eventId, TicketStatus.AVAILABLE);
        long dbReserved  = countTickets(eventId, TicketStatus.RESERVED);

        // Ambas fuentes deben coincidir exactamente
        assertThat(apiResponse.availableTickets())
                .as("API available debe coincidir con DynamoDB")
                .isEqualTo(dbAvailable);

        assertThat(apiResponse.reservedTickets())
                .as("API reserved debe coincidir con DynamoDB")
                .isEqualTo(dbReserved);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private AvailabilityResponse getAvailability(String eventId) {
        return webTestClient.get()
                .uri("/api/v1/events/{eventId}/availability", eventId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AvailabilityResponse.class)
                .returnResult()
                .getResponseBody();
    }
}