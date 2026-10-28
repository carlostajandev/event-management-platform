package com.nequi.ticketing.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Points a Spring context at the local emulators. */
public final class LocalAws {

    private LocalAws() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        registry.add("ticketing.aws.dynamodb-endpoint", LocalDynamoDb::endpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", LocalSqs::endpoint);
        registry.add("spring.cloud.aws.credentials.access-key", () -> "local");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "local");
    }
}
