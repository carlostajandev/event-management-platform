package com.nequi.ticketing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Boots the full context once per role and checks that each one reports its role and becomes ready.
 */
class TicketingApplicationTest {

    abstract static class RoleContext {

        @Autowired
        WebTestClient webTestClient;

        @MockitoBean
        DynamoDbAsyncClient dynamoDb;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void tablesAreActive() {
            when(dynamoDb.describeTable(any(Consumer.class))).thenReturn(CompletableFuture.completedFuture(
                    DescribeTableResponse.builder().table(table -> table.tableStatus(TableStatus.ACTIVE)).build()));
        }

        void assertReadyWithRole(String role) {
            webTestClient.get().uri("/actuator/health/readiness").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.status").isEqualTo("UP");
            webTestClient.get().uri("/actuator/info").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.role").isEqualTo(role);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureWebTestClient
    @ActiveProfiles("api")
    class ApiRole extends RoleContext {

        @Test
        void startsAndBecomesReady() {
            assertReadyWithRole("api");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureWebTestClient
    @ActiveProfiles("worker")
    class WorkerRole extends RoleContext {

        @Test
        void startsAndBecomesReady() {
            assertReadyWithRole("worker");
        }
    }
}
