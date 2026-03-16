package com.nequi.ticketing.infrastructure.messaging.sqs.consumer;

import com.nequi.ticketing.application.usecase.CreatePurchaseOrderService.OrderMessage;
import com.nequi.ticketing.application.usecase.ProcessOrderService;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * Polls SQS purchase-orders queue and processes each message.
 *
 * <p>At-least-once delivery guarantee: a message is deleted ONLY after
 * successful processing. On failure, the message becomes visible again
 * after the visibility timeout and retries up to maxReceiveCount=3,
 * then moves to DLQ.
 *
 * <p>Retry strategy: transient failures (network timeouts, DynamoDB throttling)
 * are retried with exponential backoff (3 attempts, 200ms base, max 2s) BEFORE
 * returning control to SQS. This reduces DLQ noise from transient blips.
 * Non-retryable errors (OrderNotFound, business logic) bypass retry and let
 * SQS handle the requeue — the SQS visibility timeout is the outer retry loop.
 */
@Component
public class SqsOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsOrderConsumer.class);

    private final SqsAsyncClient sqsClient;
    private final ProcessOrderService processOrderService;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private final int maxMessages;
    private final int waitTimeSeconds;

    public SqsOrderConsumer(
            SqsAsyncClient sqsClient,
            ProcessOrderService processOrderService,
            ObjectMapper objectMapper,
            AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.processOrderService = processOrderService;
        this.objectMapper = objectMapper;
        this.queueUrl = awsProperties.sqs().queues().purchaseOrders();
        this.maxMessages = awsProperties.sqs().consumer().maxMessages();
        this.waitTimeSeconds = awsProperties.sqs().consumer().waitTimeSeconds();
    }

    @Scheduled(fixedDelayString = "${aws.sqs.consumer.polling-interval:5000}")
    public void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitTimeSeconds)
                .build();

        Mono.fromCompletionStage(() -> sqsClient.receiveMessage(request))
                .flatMapMany(response -> Flux.fromIterable(response.messages()))
                .flatMap(this::processMessage)
                .subscribe(
                        v -> {},
                        ex -> log.error("Error in SQS poll cycle: {}", ex.getMessage())
                );
    }

    private Mono<Void> processMessage(Message message) {
        log.debug("Received SQS message: messageId={}", message.messageId());

        return Mono.fromCallable(() ->
                        objectMapper.readValue(message.body(), OrderMessage.class))
                .flatMap(orderMessage ->
                        processOrderService.process(orderMessage.orderId())
                                // Retry with exponential backoff for transient failures
                                // Non-retryable errors skip retry — SQS handles requeue
                                .retryWhen(Retry.backoff(3, Duration.ofMillis(200))
                                        .maxBackoff(Duration.ofSeconds(2))
                                        .filter(this::isRetryable)
                                        .doBeforeRetry(signal -> log.warn(
                                                "Retrying order processing attempt {}: {}",
                                                signal.totalRetriesInARow() + 1,
                                                signal.failure().getMessage()))))
                .then(deleteMessage(message))
                .doOnSuccess(v -> log.debug(
                        "Message processed and deleted: messageId={}", message.messageId()))
                .onErrorResume(ex -> {
                    // Do NOT delete — SQS requeues after visibility timeout → DLQ after maxReceiveCount
                    log.error("Message processing failed after retries, leaving for SQS retry: " +
                            "messageId={}, error={}", message.messageId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Identifies transient failures worth retrying immediately.
     * Business logic errors (OrderNotFound, state violations) are NOT retryable —
     * retrying them immediately would waste cycles with the same result.
     */
    private boolean isRetryable(Throwable ex) {
        return ex instanceof ProvisionedThroughputExceededException
                || ex instanceof SdkClientException
                || (ex.getMessage() != null && ex.getMessage().contains("timeout"));
    }

    private Mono<Void> deleteMessage(Message message) {
        return Mono.fromCompletionStage(() -> sqsClient.deleteMessage(
                        DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle())
                                .build()))
                .then();
    }
}