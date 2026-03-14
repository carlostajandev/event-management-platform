package com.nequi.ticketing.infrastructure.messaging.sqs.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nequi.ticketing.application.usecase.CreatePurchaseOrderService.OrderMessage;
import com.nequi.ticketing.application.usecase.ProcessOrderService;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

/**
 * Polls the SQS purchase-orders queue and processes each message.
 *
 * <p>Delivery guarantee: at-least-once.
 * A message is deleted from SQS ONLY after successful processing.
 * If processing fails, the message becomes visible again after the
 * visibility timeout and will be retried (up to maxReceiveCount=3).
 * After 3 failures, SQS moves the message to the Dead Letter Queue.
 *
 * <p>Idempotency: {@link ProcessOrderService} skips orders already
 * in a final state, making repeated processing safe.
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

    /**
     * Polls SQS every ${ticketing.sqs.consumer.polling-interval} ms.
     * Uses long polling to reduce costs and latency.
     */
    @Scheduled(fixedDelayString = "${aws.sqs.consumer.polling-interval:5000}")
    public void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(waitTimeSeconds)
                .build();

        Mono.fromCompletionStage(() -> sqsClient.receiveMessage(request))
                .map(response -> response.messages())
                .flatMapMany(Flux::fromIterable)
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
                        processOrderService.process(orderMessage.orderId()))
                .then(deleteMessage(message))
                .doOnSuccess(v -> log.debug(
                        "Message processed and deleted: messageId={}", message.messageId()))
                .onErrorResume(ex -> {
                    // Do NOT delete — let visibility timeout expire for retry
                    log.error("Failed to process message: messageId={}, error={}",
                            message.messageId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> deleteMessage(Message message) {
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();

        return Mono.fromCompletionStage(() -> sqsClient.deleteMessage(deleteRequest))
                .then();
    }
}
