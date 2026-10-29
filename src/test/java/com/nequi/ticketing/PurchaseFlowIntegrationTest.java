package com.nequi.ticketing;

import com.nequi.ticketing.support.LocalAws;
import com.nequi.ticketing.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Both roles in one context against the local emulators: HTTP -> DynamoDB (order + outbox) -> relay -> SQS ->
 * consumer -> confirmation, plus expiry and duplicate delivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ticketing.worker.outbox-poll-interval=100ms",
        "ticketing.worker.expiry-interval=200ms",
        "spring.cloud.aws.sqs.listener.poll-timeout=1s"})
@AutoConfigureWebTestClient
@ActiveProfiles({"api", "worker"})
class PurchaseFlowIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @TestConfiguration
    static class Clocks {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void localAws(DynamicPropertyRegistry registry) {
        LocalAws.register(registry);
    }

    @Autowired
    WebTestClient client;

    @Autowired
    MutableClock clock;

    @Autowired
    SqsAsyncClient sqs;

    @Test
    void anOrderIsAcceptedByTheConsumerAndThenSold() {
        String eventId = createEvent(10);
        String orderId = placeOrder("ana", eventId, 2);

        awaitStatus("ana", orderId, "PENDING_CONFIRMATION");
        availability(eventId).jsonPath("$.reserved").isEqualTo(0).jsonPath("$.pendingConfirmation").isEqualTo(2);

        client.post().uri("/api/v1/orders/{id}/confirm", orderId).header("X-User-Id", "ana").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SOLD");
        availability(eventId).jsonPath("$.sold").isEqualTo(2).jsonPath("$.available").isEqualTo(8);
    }

    @Test
    void aDuplicateDeliveryChangesNothing() {
        String eventId = createEvent(5);
        String orderId = placeOrder("ana", eventId, 1);
        awaitStatus("ana", orderId, "PENDING_CONFIRMATION");

        String queueUrl = sqs.getQueueUrl(r -> r.queueName("ticketing-orders")).join().queueUrl();
        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(orderId)).join();
        await().pollDelay(Duration.ofSeconds(2)).atMost(PATIENCE).untilAsserted(() ->
                assertThat(queueDepth(queueUrl)).isZero());

        availability(eventId).jsonPath("$.pendingConfirmation").isEqualTo(1).jsonPath("$.available").isEqualTo(4);
    }

    @Test
    void anUnconfirmedOrderIsReleasedWhenItsHoldExpires() {
        String eventId = createEvent(3);
        String orderId = placeOrder("luis", eventId, 3);
        awaitStatus("luis", orderId, "PENDING_CONFIRMATION");
        availability(eventId).jsonPath("$.available").isEqualTo(0);

        clock.advance(Duration.ofMinutes(10));

        awaitStatus("luis", orderId, "AVAILABLE");
        availability(eventId).jsonPath("$.available").isEqualTo(3).jsonPath("$.pendingConfirmation").isEqualTo(0);
        client.post().uri("/api/v1/orders/{id}/confirm", orderId).header("X-User-Id", "luis").exchange()
                .expectStatus().isEqualTo(409);
    }

    private void awaitStatus(String user, String orderId, String status) {
        await().atMost(PATIENCE).untilAsserted(() ->
                client.get().uri("/api/v1/orders/{id}", orderId).header("X-User-Id", user).exchange()
                        .expectStatus().isOk()
                        .expectBody().jsonPath("$.status").isEqualTo(status));
    }

    private WebTestClient.BodyContentSpec availability(String eventId) {
        return client.get().uri("/api/v1/events/{id}/availability", eventId).exchange()
                .expectStatus().isOk().expectBody();
    }

    private int queueDepth(String queueUrl) {
        Map<String, String> attributes = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl)
                        .attributeNamesWithStrings("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"))
                .join().attributesAsStrings();
        return Integer.parseInt(attributes.get("ApproximateNumberOfMessages"))
                + Integer.parseInt(attributes.get("ApproximateNumberOfMessagesNotVisible"));
    }

    private String createEvent(int capacity) {
        Map<String, Object> body = Map.of("name", "Rock Fest", "venue", "Movistar Arena",
                "date", clock.instant().plus(Duration.ofDays(30)).toString(), "capacity", capacity,
                "ticketPrice", 150000, "currency", "COP");
        return client.post().uri("/api/v1/events").header("X-User-Role", "admin").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
    }

    private String placeOrder(String user, String eventId, int quantity) {
        return client.post().uri("/api/v1/orders").header("X-User-Id", user).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("eventId", eventId, "quantity", quantity))
                .exchange().expectStatus().isAccepted()
                .expectBody(Map.class).returnResult().getResponseBody().get("orderId").toString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
