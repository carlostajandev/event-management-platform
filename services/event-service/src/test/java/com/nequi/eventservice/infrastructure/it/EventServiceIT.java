package com.nequi.eventservice.infrastructure.it;

import com.nequi.eventservice.EventServiceApplication;
import com.nequi.eventservice.application.dto.AvailabilityResponse;
import com.nequi.eventservice.application.dto.CreateEventRequest;
import com.nequi.eventservice.application.dto.EventResponse;
import com.nequi.eventservice.application.dto.VenueRequest;
import com.nequi.shared.domain.model.EventStatus;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;

/**
 * Integration tests for event-service HTTP endpoints.
 *
 * <p>Starts a real Spring Boot context against a LocalStack DynamoDB instance.
 * The {@code DynamoDbTableInitializer} bean (active on "test" profile) creates
 * the {@code emp-events} table automatically on startup.
 *
 * <p>These tests are picked up by maven-failsafe (suffix {@code IT}).
 */
@Slf4j
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = EventServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DisplayName("EventService — Integration Tests")
class EventServiceIT {

    // ── Shared LocalStack container (one per class, re-used across all tests) ──

    @Container
    static final LocalStackContainer LOCAL_STACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4.0"))
                    .withServices(DYNAMODB);

    // ── Override application config to point at LocalStack ────────────────────

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.dynamodb.endpoint",
                () -> LOCAL_STACK.getEndpointOverride(DYNAMODB).toString());
        registry.add("aws.region",
                () -> LOCAL_STACK.getRegion());
        // Dummy credentials — LocalStack accepts any value
        registry.add("aws.accessKeyId",  () -> LOCAL_STACK.getAccessKey());
        registry.add("aws.secretAccessKey", () -> LOCAL_STACK.getSecretKey());
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a valid {@link CreateEventRequest} with a unique name so tests
     * do not interfere with each other when listing by status.
     */
    private CreateEventRequest validEventRequest(String name, int capacity) {
        return new CreateEventRequest(
                name,
                "Integration test event for " + name,
                new VenueRequest(
                        "Testcontainers Arena",
                        "Calle 100 # 15-30",
                        "Bogota",
                        "Colombia",
                        50_000
                ),
                Instant.now().plus(30, ChronoUnit.DAYS),
                new BigDecimal("75000.00"),
                "COP",
                capacity
        );
    }

    private CreateEventRequest validEventRequest(String name) {
        return validEventRequest(name, 10);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/events should create event and return 201 with all response fields")
    void shouldCreateEventAndReturn201() {
        String uniqueName = "Feid Live " + UUID.randomUUID();
        CreateEventRequest request = validEventRequest(uniqueName, 500);

        webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventResponse.class)
                .value(response -> {
                    assertThat(response.id()).isNotNull().isNotBlank();
                    assertThat(response.name()).isEqualTo(uniqueName);
                    assertThat(response.description()).isEqualTo("Integration test event for " + uniqueName);
                    assertThat(response.status()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(response.totalCapacity()).isEqualTo(500);
                    assertThat(response.availableCount()).isEqualTo(500);
                    assertThat(response.ticketPrice()).isEqualByComparingTo(new BigDecimal("75000.00"));
                    assertThat(response.currency()).isEqualTo("COP");
                    assertThat(response.venue()).isNotNull();
                    assertThat(response.venue().name()).isEqualTo("Testcontainers Arena");
                    assertThat(response.venue().city()).isEqualTo("Bogota");
                    assertThat(response.createdAt()).isNotNull();
                    assertThat(response.updatedAt()).isNotNull();
                });

        log.info("shouldCreateEventAndReturn201 — PASSED");
    }

    @Test
    @DisplayName("GET /api/v1/events/{eventId} should return 200 with event after creation")
    void shouldGetEventById() {
        // Arrange — create the event
        String uniqueName = "Shakira Bogota " + UUID.randomUUID();
        EventResponse created = webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validEventRequest(uniqueName, 200))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        String eventId = created.id();
        assertThat(eventId).isNotBlank();

        // Act & Assert — fetch by id
        webTestClient.get()
                .uri("/api/v1/events/{id}", eventId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(EventResponse.class)
                .value(response -> {
                    assertThat(response.id()).isEqualTo(eventId);
                    assertThat(response.name()).isEqualTo(uniqueName);
                    assertThat(response.status()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(response.totalCapacity()).isEqualTo(200);
                });

        log.info("shouldGetEventById — PASSED for eventId={}", eventId);
    }

    @Test
    @DisplayName("GET /api/v1/events/{eventId} with non-existent id should return 404")
    void shouldReturn404ForUnknownEvent() {
        String nonExistentId = UUID.randomUUID().toString();

        webTestClient.get()
                .uri("/api/v1/events/{id}", nonExistentId)
                .exchange()
                .expectStatus().isNotFound();

        log.info("shouldReturn404ForUnknownEvent — PASSED for id={}", nonExistentId);
    }

    @Test
    @DisplayName("POST /api/v1/events with missing name should return 400")
    void shouldReturn400ForInvalidRequest() {
        // Build invalid request: name is blank
        String invalidBody = """
                {
                  "name": "",
                  "description": "Test event",
                  "venue": {
                    "name": "Arena",
                    "address": "Calle 1",
                    "city": "Bogota",
                    "country": "Colombia",
                    "capacity": 1000
                  },
                  "eventDate": "%s",
                  "ticketPrice": 50000.00,
                  "currency": "COP",
                  "totalCapacity": 100
                }
                """.formatted(Instant.now().plus(10, ChronoUnit.DAYS));

        webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalidBody)
                .exchange()
                .expectStatus().isBadRequest();

        log.info("shouldReturn400ForInvalidRequest — PASSED");
    }

    @Test
    @DisplayName("GET /api/v1/events/{eventId}/availability should return 200 with available=true and correct count")
    void shouldGetAvailability() {
        // Arrange — create event with 10 seats
        String uniqueName = "Availability Test " + UUID.randomUUID();
        EventResponse created = webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validEventRequest(uniqueName, 10))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        String eventId = created.id();

        // Act & Assert
        webTestClient.get()
                .uri("/api/v1/events/{id}/availability", eventId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AvailabilityResponse.class)
                .value(response -> {
                    assertThat(response.eventId()).isEqualTo(eventId);
                    assertThat(response.availableCount()).isEqualTo(10);
                    assertThat(response.available()).isTrue();
                });

        log.info("shouldGetAvailability — PASSED for eventId={}", eventId);
    }

    @Test
    @DisplayName("GET /api/v1/events?status=ACTIVE should return list containing the created event")
    void shouldListEventsByStatus() {
        // Arrange — create an event with a unique-enough name we can find in results
        String uniqueName = "ListByStatus " + UUID.randomUUID();
        EventResponse created = webTestClient.post()
                .uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validEventRequest(uniqueName, 50))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        String eventId = created.id();

        // Act & Assert — list by ACTIVE status
        webTestClient.get()
                .uri("/api/v1/events?status=ACTIVE")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EventResponse.class)
                .value((List<EventResponse> events) -> {
                    assertThat(events).isNotEmpty();
                    boolean found = events.stream()
                            .anyMatch(e -> eventId.equals(e.id()));
                    assertThat(found)
                            .as("Newly created event with id=%s should appear in ACTIVE list", eventId)
                            .isTrue();
                });

        log.info("shouldListEventsByStatus — PASSED for eventId={}", eventId);
    }
}
