package com.nequi.ticketing;

import com.nequi.ticketing.support.LocalDynamoDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The api role end to end over HTTP, against DynamoDB Local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("api")
class ApiFlowIntegrationTest {

    @Autowired
    WebTestClient client;

    @DynamicPropertySource
    static void dynamoDb(DynamicPropertyRegistry registry) {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        registry.add("ticketing.aws.dynamodb-endpoint", LocalDynamoDb::endpoint);
    }

    @Test
    void purchaseFlowWithIdempotentRetries() {
        String eventId = createEvent(10);

        Map<?, ?> original = placeOrder("ana", "click-1", eventId, 2)
                .expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody();
        String orderId = original.get("orderId").toString();

        // Double click: same key, same body -> same order, tickets held once.
        placeOrder("ana", "click-1", eventId, 2).expectStatus().isAccepted()
                .expectBody().jsonPath("$.orderId").isEqualTo(orderId)
                .jsonPath("$.createdAt").isEqualTo(original.get("createdAt"));
        // Same key, different body -> rejected.
        placeOrder("ana", "click-1", eventId, 3).expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // Keys are scoped per caller: another user may reuse the same key value.
        placeOrder("luis", "click-1", eventId, 1).expectStatus().isAccepted();

        client.get().uri("/api/v1/events/{id}/availability", eventId).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.available").isEqualTo(7)
                .jsonPath("$.reserved").isEqualTo(3);

        client.get().uri("/api/v1/orders/{id}", orderId).header("X-User-Id", "ana").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("RESERVED");
        client.get().uri("/api/v1/orders/{id}", orderId).header("X-User-Id", "luis").exchange()
                .expectStatus().isNotFound();
        // Not accepted by the consumer yet.
        client.post().uri("/api/v1/orders/{id}/confirm", orderId).header("X-User-Id", "ana").exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("INVALID_STATE");
    }

    @Test
    void soldOutAndComplimentaryTickets() {
        String eventId = createEvent(3);

        client.post().uri("/api/v1/events/{id}/complimentary", eventId)
                .header("X-User-Role", "admin").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("recipientUserId", "vip", "quantity", 1))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("COMPLIMENTARY");
        placeOrder("ana", key(), eventId, 2).expectStatus().isAccepted();
        placeOrder("luis", key(), eventId, 1).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("SOLD_OUT");

        client.get().uri("/api/v1/events/{id}/availability", eventId).exchange()
                .expectBody()
                .jsonPath("$.available").isEqualTo(0)
                .jsonPath("$.reserved").isEqualTo(2)
                .jsonPath("$.complimentary").isEqualTo(1);
    }

    @Test
    void unknownEventsAreNotFound() {
        client.get().uri("/api/v1/events/{id}", "missing").exchange().expectStatus().isNotFound();
        placeOrder("ana", key(), "missing", 1).expectStatus().isNotFound();
    }

    private String createEvent(int capacity) {
        Map<String, Object> body = Map.of("name", "Rock Fest", "venue", "Movistar Arena",
                "date", Instant.now().plus(Duration.ofDays(30)).toString(), "capacity", capacity,
                "ticketPrice", 150000, "currency", "COP");
        return client.post().uri("/api/v1/events").header("X-User-Role", "admin").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
    }

    private WebTestClient.ResponseSpec placeOrder(String user, String key, String eventId, int quantity) {
        return client.post().uri("/api/v1/orders").header("X-User-Id", user).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("eventId", eventId, "quantity", quantity))
                .exchange();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
