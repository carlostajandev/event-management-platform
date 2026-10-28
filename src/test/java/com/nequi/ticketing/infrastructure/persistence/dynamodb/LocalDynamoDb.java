package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Duration;

/**
 * One DynamoDB Local container per test JVM. Tables are created by the same script docker compose uses,
 * so tests and the local stack share a single schema definition.
 */
final class LocalDynamoDb {

    static final TicketingProperties PROPERTIES = new TicketingProperties(
            new TicketingProperties.Aws("us-east-1", "", Duration.ofSeconds(5), Duration.ofSeconds(2)),
            new TicketingProperties.Tables("ticketing-events", "ticketing-orders", "ticketing-idempotency",
                    "ticketing-audit", "ticketing-outbox"));

    private static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withNetwork(NETWORK)
            .withNetworkAliases("dynamodb")
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    private static DynamoDbAsyncClient client;

    private LocalDynamoDb() {
    }

    static synchronized DynamoDbAsyncClient client() {
        if (client == null) {
            DYNAMODB.start();
            createTables();
            client = DynamoDbAsyncClient.builder()
                    .endpointOverride(URI.create("http://%s:%d".formatted(DYNAMODB.getHost(), DYNAMODB.getMappedPort(8000))))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                    .build();
        }
        return client;
    }

    @SuppressWarnings("resource")
    private static void createTables() {
        try (GenericContainer<?> init = new GenericContainer<>("amazon/aws-cli:2.39.1")
                .withNetwork(NETWORK)
                .withEnv("AWS_REGION", "us-east-1")
                .withEnv("AWS_ACCESS_KEY_ID", "local")
                .withEnv("AWS_SECRET_ACCESS_KEY", "local")
                .withEnv("DYNAMODB_ENDPOINT", "http://dynamodb:8000")
                .withCopyFileToContainer(MountableFile.forHostPath("infrastructure/local/dynamodb-init.sh"), "/init.sh")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "/init.sh"))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
            init.start();
        }
    }
}
