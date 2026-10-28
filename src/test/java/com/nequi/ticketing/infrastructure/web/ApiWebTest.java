package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.event.CreateEventCommand;
import com.nequi.ticketing.application.event.CreateEventUseCase;
import com.nequi.ticketing.application.event.GetAvailabilityUseCase;
import com.nequi.ticketing.application.event.GetEventUseCase;
import com.nequi.ticketing.application.event.ListEventsUseCase;
import com.nequi.ticketing.application.order.ConfirmOrderUseCase;
import com.nequi.ticketing.application.order.GetOrderUseCase;
import com.nequi.ticketing.application.order.IssueComplimentaryUseCase;
import com.nequi.ticketing.application.order.PlaceOrderCommand;
import com.nequi.ticketing.application.order.PlaceOrderUseCase;
import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.exception.HoldExpiredException;
import com.nequi.ticketing.domain.exception.IdempotencyKeyReusedException;
import com.nequi.ticketing.domain.exception.IllegalTransitionException;
import com.nequi.ticketing.domain.exception.InsufficientTicketsException;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.model.Money;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.TicketState;
import com.nequi.ticketing.domain.model.UserId;
import com.nequi.ticketing.infrastructure.config.ApiProperties;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.TransactionContentionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Rate limits are covered by RequestProtectionFilterTest; here they would make results depend on test order.
@WebFluxTest(controllers = {EventController.class, OrderController.class}, properties = {
        "ticketing.api.per-user.requests-per-second=0",
        "ticketing.api.per-client-ip.requests-per-second=0"})
@EnableConfigurationProperties(ApiProperties.class)
@Import(HeaderCurrentCaller.class)
@ActiveProfiles("api")
class ApiWebTest {

    private static final Instant NOW = Instant.parse("2026-11-01T15:00:00Z");
    private static final Event EVENT = Event.create(new EventId("event-1"), "Rock Fest", "Movistar Arena",
            NOW.plus(Duration.ofDays(30)), 100, Money.of("150000", "COP"), NOW);
    private static final Order ORDER = Order.place(new OrderId("order-1"), EVENT, new UserId("user-1"), 2, NOW).order();

    @Autowired
    WebTestClient client;

    @MockitoBean CreateEventUseCase createEvent;
    @MockitoBean GetEventUseCase getEvent;
    @MockitoBean ListEventsUseCase listEvents;
    @MockitoBean GetAvailabilityUseCase getAvailability;
    @MockitoBean IssueComplimentaryUseCase issueComplimentary;
    @MockitoBean PlaceOrderUseCase placeOrder;
    @MockitoBean GetOrderUseCase getOrder;
    @MockitoBean ConfirmOrderUseCase confirmOrder;

    @Test
    void placesAnOrderAndAnswers202WithItsLocation() {
        when(placeOrder.execute(any())).thenReturn(Mono.just(ORDER));

        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 2))
                .expectStatus().isAccepted()
                .expectHeader().location("/api/v1/orders/order-1")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo("order-1")
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.totalAmount").isEqualTo(300000.00)
                .jsonPath("$.expiresAt").isEqualTo("2026-11-01T15:10:00Z");

        ArgumentCaptor<PlaceOrderCommand> command = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(placeOrder).execute(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(new UserId("user-1"));
        assertThat(command.getValue().idempotencyKey()).isEqualTo(new IdempotencyKey("user-1", "key-1"));
    }

    @Test
    void theSameRequestProducesTheSameFingerprint() {
        when(placeOrder.execute(any())).thenReturn(Mono.just(ORDER));
        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 2)).expectStatus().isAccepted();
        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 3)).expectStatus().isAccepted();

        ArgumentCaptor<PlaceOrderCommand> commands = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(placeOrder, times(2)).execute(commands.capture());
        assertThat(commands.getAllValues().get(0).requestHash()).isNotEqualTo(commands.getAllValues().get(1).requestHash())
                .isEqualTo(RequestFingerprint.of("event-1", 2));
    }

    @Test
    void anOrderRequiresAnIdempotencyKeyACallerAndAValidBody() {
        client.post().uri("/api/v1/orders").header("X-User-Id", "user-1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("eventId", "event-1", "quantity", 1))
                .exchange().expectStatus().isBadRequest();
        postOrder(null, "key-1", Map.of("eventId", "event-1", "quantity", 1))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("CALLER_NOT_IDENTIFIED");
        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 7)).expectStatus().isBadRequest();
        postOrder("user-1", "key-1", Map.of("eventId", "", "quantity", 1)).expectStatus().isBadRequest();
        client.post().uri("/api/v1/orders").header("X-User-Id", "user-1").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange().expectStatus().isBadRequest();

        verify(placeOrder, never()).execute(any());
    }

    @Test
    void rejectsAnInvalidCallerIdentity() {
        postOrder("u".repeat(65), "key-1", Map.of("eventId", "event-1", "quantity", 1))
                .expectStatus().isEqualTo(422);
        verify(placeOrder, never()).execute(any());
    }

    @Test
    void rejectsOversizedBodies() {
        postOrder("user-1", "key-1", Map.of("eventId", "e".repeat(20_000), "quantity", 1))
                .expectStatus().value(status -> assertThat(status).isIn(400, 413));
    }

    static Stream<Arguments> domainErrors() {
        return Stream.of(
                Arguments.of(new DomainValidationException("bad"), 422, "VALIDATION_FAILED"),
                Arguments.of(new NotFoundException("Event", "x"), 404, "NOT_FOUND"),
                Arguments.of(new InsufficientTicketsException(new EventId("e"), 2, 1), 409, "SOLD_OUT"),
                Arguments.of(new IllegalTransitionException(TicketState.RESERVED, TicketState.SOLD), 409, "INVALID_STATE"),
                Arguments.of(new HoldExpiredException(new OrderId("o"), NOW), 409, "HOLD_EXPIRED"),
                Arguments.of(new OrderStateChangedException(new OrderId("o"), TicketState.RESERVED), 409, "STATE_CHANGED"),
                Arguments.of(new DuplicateRequestException(new IdempotencyKey("u", "k")), 409, "DUPLICATE_REQUEST"),
                Arguments.of(new IdempotencyKeyReusedException(new IdempotencyKey("u", "k")), 422, "IDEMPOTENCY_KEY_REUSED"));
    }

    @ParameterizedTest
    @MethodSource("domainErrors")
    void mapsEveryDomainErrorToAProblemDetail(RuntimeException error, int status, String code) {
        when(placeOrder.execute(any())).thenReturn(Mono.error(error));

        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 1))
                .expectStatus().isEqualTo(status)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.detail").isEqualTo(error.getMessage());
    }

    @Test
    void contentionAsksTheClientToRetry() {
        when(placeOrder.execute(any())).thenReturn(Mono.error(new TransactionContentionException(new RuntimeException("conflict"))));

        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 1))
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.code").isEqualTo("CONTENTION");
    }

    @Test
    void datastoreTimeoutAsksTheClientToRetryWithTheSameKey() {
        when(placeOrder.execute(any())).thenReturn(Mono.error(ApiCallTimeoutException.create(2000)));

        postOrder("user-1", "key-1", Map.of("eventId", "event-1", "quantity", 1))
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.code").isEqualTo("TIMEOUT");
    }

    @Test
    void readsAndConfirmsOrdersAsTheCaller() {
        Order sold = ORDER.startConfirmation(NOW).order().confirmSale(NOW).order();
        when(getOrder.execute(ORDER.id(), new UserId("user-1"))).thenReturn(Mono.just(ORDER));
        when(confirmOrder.execute(ORDER.id(), new UserId("user-1"))).thenReturn(Mono.just(sold));

        client.get().uri("/api/v1/orders/order-1").header("X-User-Id", "user-1").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RESERVED");
        client.post().uri("/api/v1/orders/order-1/confirm").header("X-User-Id", "user-1").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SOLD");
    }

    @Test
    void adminsCreateEvents() {
        when(createEvent.execute(any())).thenReturn(Mono.just(EVENT));

        client.post().uri("/api/v1/events").header("X-User-Role", "admin").header("Idempotency-Key", "ev-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(eventBody())
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/api/v1/events/event-1")
                .expectBody().jsonPath("$.id").isEqualTo("event-1").jsonPath("$.currency").isEqualTo("COP");

        ArgumentCaptor<CreateEventCommand> command = ArgumentCaptor.forClass(CreateEventCommand.class);
        verify(createEvent).execute(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(new IdempotencyKey("admin", "ev-1"));
        assertThat(command.getValue().ticketPrice()).isEqualTo(Money.of("150000", "COP"));
    }

    @Test
    void onlyAdminsCreateEventsOrIssueComplimentaryTickets() {
        client.post().uri("/api/v1/events").header("X-User-Id", "user-1").header("Idempotency-Key", "ev-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(eventBody())
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.post().uri("/api/v1/events/event-1/complimentary").header("Idempotency-Key", "c-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("recipientUserId", "vip-1", "quantity", 2))
                .exchange().expectStatus().isForbidden();

        verify(createEvent, never()).execute(any());
        verify(issueComplimentary, never()).execute(any());
    }

    @Test
    void validatesEventsBeforeCallingTheUseCase() {
        Map<String, Object> pastAndNegative = Map.of("name", "Rock Fest", "venue", "Arena", "date", "2020-01-01T00:00:00Z",
                "capacity", -5, "ticketPrice", 10, "currency", "cop");

        client.post().uri("/api/v1/events").header("X-User-Role", "admin").header("Idempotency-Key", "ev-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(pastAndNegative)
                .exchange().expectStatus().isBadRequest();
        verify(createEvent, never()).execute(any());
    }

    @Test
    void issuesComplimentaryTickets() {
        Order courtesy = Order.complimentary(new OrderId("order-2"), EVENT, new UserId("vip-1"), 2, NOW).order();
        when(issueComplimentary.execute(any())).thenReturn(Mono.just(courtesy));

        client.post().uri("/api/v1/events/event-1/complimentary").header("X-User-Role", "admin").header("Idempotency-Key", "c-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("recipientUserId", "vip-1", "quantity", 2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("COMPLIMENTARY").jsonPath("$.totalAmount").isEqualTo(0);
    }

    @Test
    void queriesEventsAndAvailability() {
        when(listEvents.execute(5)).thenReturn(Flux.just(EVENT));
        when(getEvent.execute(EVENT.id())).thenReturn(Mono.just(EVENT));
        when(getAvailability.execute(EVENT.id())).thenReturn(Mono.just(new Inventory(EVENT.id(), 100, 90, 6, 2, 1, 1)));

        client.get().uri("/api/v1/events?limit=5").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].name").isEqualTo("Rock Fest");
        client.get().uri("/api/v1/events/event-1").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.capacity").isEqualTo(100);
        client.get().uri("/api/v1/events/event-1/availability").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.available").isEqualTo(90)
                .jsonPath("$.reserved").isEqualTo(6)
                .jsonPath("$.pendingConfirmation").isEqualTo(2)
                .jsonPath("$.sold").isEqualTo(1);
    }

    @Test
    void rejectsMalformedIds() {
        client.get().uri("/api/v1/events/" + "x".repeat(65)).exchange()
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
        verify(getEvent, never()).execute(eq(new EventId("x")));
    }

    private WebTestClient.ResponseSpec postOrder(String user, String key, Map<String, Object> body) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            request.header("X-User-Id", user);
        }
        return request.header("Idempotency-Key", key).bodyValue(body).exchange();
    }

    private static Map<String, Object> eventBody() {
        return Map.of("name", "Rock Fest", "venue", "Movistar Arena", "date", NOW.plus(Duration.ofDays(3650)).toString(),
                "capacity", 100, "ticketPrice", 150000, "currency", "COP");
    }
}
