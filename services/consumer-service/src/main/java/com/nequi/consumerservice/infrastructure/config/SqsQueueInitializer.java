package com.nequi.consumerservice.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Creates SQS queues required by consumer-service on startup.
 *
 * <p>Only active on {@code local} and {@code test} profiles. In production,
 * queues are provisioned by Terraform.
 *
 * <p>Queues created:
 * <ol>
 *   <li>{@code emp-purchase-orders-dlq} — Dead Letter Queue (created first for ARN)</li>
 *   <li>{@code emp-purchase-orders} — Main queue with RedrivePolicy: max 3 receive counts → DLQ</li>
 * </ol>
 *
 * <p>Both queues are standard (non-FIFO) for simplicity. FIFO would require
 * {@code .fifo} suffix and {@code ContentBasedDeduplication} attribute.
 * Consumer-side idempotency ({@link com.nequi.consumerservice.application.usecase.ProcessOrderService})
 * handles duplicate deliveries from standard queue redelivery.
 */
@Slf4j
@Component
@Profile({"local", "test"})
public class SqsQueueInitializer {

    private final SqsAsyncClient sqsAsyncClient;
    private final String         purchaseOrdersQueueName;
    private final String         purchaseOrdersDlqName;

    public SqsQueueInitializer(
            SqsAsyncClient sqsAsyncClient,
            @Value("${aws.sqs.queue.purchase-orders-name:emp-purchase-orders}") String purchaseOrdersQueueName,
            @Value("${aws.sqs.queue.purchase-orders-dlq-name:emp-purchase-orders-dlq}") String purchaseOrdersDlqName) {
        this.sqsAsyncClient         = sqsAsyncClient;
        this.purchaseOrdersQueueName = purchaseOrdersQueueName;
        this.purchaseOrdersDlqName   = purchaseOrdersDlqName;
    }

    @PostConstruct
    public void initializeQueues() {
        try {
            // Step 1: Create DLQ first — needed to get the ARN for the main queue's redrive policy
            String dlqUrl = createQueue(purchaseOrdersDlqName, Map.of());
            String dlqArn = getQueueArn(dlqUrl);
            log.info("DLQ ready: name={}, arn={}", purchaseOrdersDlqName, dlqArn);

            // Step 2: Create main queue with RedrivePolicy pointing to DLQ
            String redrivePolicy = buildRedrivePolicy(dlqArn, 3);
            Map<String, String> mainQueueAttributes = new HashMap<>();
            mainQueueAttributes.put(QueueAttributeName.REDRIVE_POLICY.toString(), redrivePolicy);
            mainQueueAttributes.put(QueueAttributeName.VISIBILITY_TIMEOUT.toString(), "30"); // 30 seconds
            mainQueueAttributes.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD.toString(), "86400"); // 24h

            String mainQueueUrl = createQueue(purchaseOrdersQueueName, mainQueueAttributes);
            log.info("Main queue ready: name={}, url={}", purchaseOrdersQueueName, mainQueueUrl);

        } catch (Exception ex) {
            log.error("Failed to initialize SQS queues: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Cannot initialize SQS queues", ex);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createQueue(String queueName, Map<String, String> attributes) throws ExecutionException, InterruptedException {
        log.info("Creating SQS queue: {}", queueName);
        try {
            CreateQueueRequest.Builder builder = CreateQueueRequest.builder()
                    .queueName(queueName);

            if (!attributes.isEmpty()) {
                Map<QueueAttributeName, String> queueAttributes = new HashMap<>();
                attributes.forEach((k, v) -> queueAttributes.put(QueueAttributeName.fromValue(k), v));
                builder.attributes(queueAttributes);
            }

            String queueUrl = sqsAsyncClient.createQueue(builder.build())
                    .get()
                    .queueUrl();

            log.info("SQS queue created: name={}, url={}", queueName, queueUrl);
            return queueUrl;

        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof QueueNameExistsException) {
                log.info("SQS queue already exists, fetching URL: {}", queueName);
                return sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).get().queueUrl();
            }
            throw ex;
        }
    }

    private String getQueueArn(String queueUrl) throws ExecutionException, InterruptedException {
        return sqsAsyncClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build()
        ).get().attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private String buildRedrivePolicy(String dlqArn, int maxReceiveCount) {
        return String.format("{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":%d}",
                dlqArn, maxReceiveCount);
    }
}
