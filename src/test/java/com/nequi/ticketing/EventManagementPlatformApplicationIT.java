package com.nequi.ticketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Application context smoke test.
 *
 * <p>Verifies that the full Spring Boot context starts without errors
 * with LocalStack (DynamoDB + SQS) and all beans wired correctly.
 *
 * <p>This is the first test that should pass after any major refactor —
 * if the context fails to load, all other tests are meaningless.
 *
 * <p>Additional assertions verify that critical beans are present
 * (use cases, repositories, web handlers) and that the actuator
 * health endpoint reports UP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DisplayName("Application Context — Smoke Test")
class EventManagementPlatformApplicationIT {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private WebTestClient webTestClient;

	@Test
	@DisplayName("Spring context loads successfully with all beans")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	@DisplayName("All critical use-case beans are registered")
	void criticalBeansArePresent() {
		assertThat(context.containsBean("createEventService")).isTrue();
		assertThat(context.containsBean("reserveTicketsService")).isTrue();
		assertThat(context.containsBean("createPurchaseOrderService")).isTrue();
		assertThat(context.containsBean("processOrderService")).isTrue();
		assertThat(context.containsBean("getAvailabilityService")).isTrue();
		assertThat(context.containsBean("releaseExpiredReservationsService")).isTrue();
	}

	@Test
	@DisplayName("All repository beans are registered")
	void repositoryBeansArePresent() {
		assertThat(context.containsBean("eventDynamoDbRepository")).isTrue();
		assertThat(context.containsBean("ticketDynamoDbRepository")).isTrue();
		assertThat(context.containsBean("orderDynamoDbRepository")).isTrue();
		assertThat(context.containsBean("idempotencyDynamoDbRepository")).isTrue();
	}

	@Test
	@DisplayName("GET /actuator/health returns UP")
	void actuatorHealthReturnsUp() {
		webTestClient.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	@DisplayName("GET /actuator/info returns application metadata")
	void actuatorInfoReturnsMetadata() {
		webTestClient.get()
				.uri("/actuator/info")
				.exchange()
				.expectStatus().isOk();
	}
}