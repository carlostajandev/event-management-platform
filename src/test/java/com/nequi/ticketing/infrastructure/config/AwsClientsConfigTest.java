package com.nequi.ticketing.infrastructure.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AwsClientsConfigTest {

    private final AwsClientsConfig config = new AwsClientsConfig();

    @Test
    void usesEndpointOverrideForLocalEmulators() {
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient(properties("http://dynamodb:8000"))) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create("http://dynamodb:8000"));
            assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                    .contains(Duration.ofSeconds(2));
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout())
                    .contains(Duration.ofMillis(500));
        }
    }

    @Test
    void usesRegionalEndpointWhenNoOverrideIsSet() {
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient(properties(""))) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    private static TicketingProperties properties(String endpoint) {
        return new TicketingProperties(
                new TicketingProperties.Aws("us-east-1", endpoint, Duration.ofSeconds(2), Duration.ofMillis(500)),
                new TicketingProperties.Tables("events", "orders", "idempotency", "audit", "outbox"));
    }
}
