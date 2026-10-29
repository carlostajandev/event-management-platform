package com.nequi.ticketing;

import com.nequi.ticketing.support.LocalAws;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Boots the full context once per role against the local emulators and checks that each one reports its
 * role and becomes ready.
 */
class TicketingApplicationTest {

    @DynamicPropertySource
    static void localAws(DynamicPropertyRegistry registry) {
        LocalAws.register(registry);
    }

    abstract static class RoleContext {

        @Autowired
        WebTestClient webTestClient;

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

        @Test
        void exposesNoApiEndpoints() {
            webTestClient.get().uri("/api/v1/events").exchange().expectStatus().isNotFound();
        }
    }
}
