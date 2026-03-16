package com.nequi.ticketing.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for AWS credentials and endpoints.
 * Reads from application.yml under the "aws" prefix.
 *
 * <p>In local/test profiles, endpoint overrides point to
 * DynamoDB Local and LocalStack instead of real AWS services.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String region,
        DynamoDbProperties dynamodb,
        SqsProperties sqs
) {

    public record DynamoDbProperties(
            String endpoint,
            TablesProperties tables
    ) {}

    public record TablesProperties(
            String events,
            String tickets,
            String orders,
            String idempotency,
            String audit,
            String shedlock
    ) {}

    public record SqsProperties(
            String endpoint,
            QueuesProperties queues,
            ConsumerProperties consumer
    ) {}

    public record QueuesProperties(
            String purchaseOrders,
            String purchaseOrdersDlq
    ) {}

    public record ConsumerProperties(
            int maxMessages,
            int visibilityTimeout,
            int waitTimeSeconds,
            long pollingInterval
    ) {}
}