package com.nequi.ticketing.infrastructure.messaging.sqs.producer;

import com.nequi.ticketing.application.port.out.MessagePublisher;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * SQS implementation of {@link MessagePublisher}.
 *
 * <p>Publishes messages to the purchase-orders queue.
 * Uses at-least-once delivery — the consumer must be idempotent.
 */
@Component
public class SqsMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsMessagePublisher.class);

    private final SqsAsyncClient sqsClient;
    private final String queueUrl;

    public SqsMessagePublisher(SqsAsyncClient sqsClient, AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.queueUrl = awsProperties.sqs().queues().purchaseOrders();
    }

    @CircuitBreaker(name = "sqs")
    @Retry(name = "sqs-publish")
    @Override
    public Mono<String> publishPurchaseOrder(String messageBody, String messageGroupId) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();

        return Mono.fromCompletionStage(() -> sqsClient.sendMessage(request))
                .map(response -> response.messageId())
                .doOnSuccess(msgId -> log.debug(
                        "Message published to SQS: messageId={}", msgId))
                .doOnError(ex -> log.error(
                        "Failed to publish message to SQS: {}", ex.getMessage()));
    }
}