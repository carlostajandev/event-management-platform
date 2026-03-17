package com.nequi.consumerservice.infrastructure.messaging.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nequi.consumerservice.application.usecase.ProcessOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.List;

/**
 * SQS message consumer for ORDER_PLACED events — raw AWS SDK v2 scheduled poller.
 *
 * <p>Replaces the Spring Cloud AWS {@code @SqsListener} approach, which is incompatible
 * with Spring Boot 4.x (PropertyMapper.alwaysApplyingWhenNonNull() was removed).
 *
 * <p>Polling strategy:
 * <ul>
 *   <li>Polls every 2 seconds (fixedDelay — measured from completion of previous poll)</li>
 *   <li>Receives up to 10 messages per poll</li>
 *   <li>Deletes message from SQS only after successful processing (at-least-once delivery)</li>
 *   <li>On failure, message becomes visible again after the queue's visibility timeout (30s),
 *       up to maxReceiveCount=3 before moving to DLQ</li>
 * </ul>
 *
 * <p>Queue URL is resolved lazily on first poll via GetQueueUrl — handles startup ordering
 * where SqsQueueInitializer may not have created the queue yet.
 */
@Slf4j
@Component
public class OrderMessageConsumer {

    private final SqsAsyncClient     sqsAsyncClient;
    private final ProcessOrderService processOrderService;
    private final ObjectMapper        objectMapper;
    private final String              queueName;

    private volatile String queueUrl;

    public OrderMessageConsumer(
            SqsAsyncClient sqsAsyncClient,
            ProcessOrderService processOrderService,
            ObjectMapper objectMapper,
            @Value("${aws.sqs.queue.purchase-orders-name:emp-purchase-orders}") String queueName) {
        this.sqsAsyncClient      = sqsAsyncClient;
        this.processOrderService = processOrderService;
        this.objectMapper        = objectMapper;
        this.queueName           = queueName;
    }

    @Scheduled(fixedDelay = 2_000)
    public void poll() {
        if (queueUrl == null) {
            try {
                queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).get().queueUrl();
                log.info("Resolved SQS queue URL: {}", queueUrl);
            } catch (Exception e) {
                log.warn("SQS queue '{}' not ready yet, will retry: {}", queueName, e.getMessage());
                return;
            }
        }

        try {
            ReceiveMessageResponse response = sqsAsyncClient.receiveMessage(r -> r
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(0)
            ).get();

            List<Message> messages = response.messages();
            if (!messages.isEmpty()) {
                log.debug("Received {} SQS messages", messages.size());
                for (Message message : messages) {
                    processMessage(message);
                }
            }
        } catch (Exception e) {
            log.error("Error polling SQS queue '{}': {}", queueName, e.getMessage(), e);
        }
    }

    private void processMessage(Message message) {
        PurchaseOrderMessage orderMessage;
        try {
            orderMessage = objectMapper.readValue(message.body(), PurchaseOrderMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialize SQS message body: messageId={}, body={}, error={}",
                    message.messageId(), message.body(), e.getMessage());
            // Poison message — delete it so it doesn't block the queue forever
            deleteMessage(message);
            return;
        }

        log.info("Processing SQS message: orderId={}, reservationId={}, userId={}",
                orderMessage.orderId(), orderMessage.reservationId(), orderMessage.userId());

        try {
            processOrderService.process(orderMessage.orderId()).block();
            deleteMessage(message);
            log.info("SQS message processed successfully: orderId={}", orderMessage.orderId());
        } catch (Exception e) {
            log.error("Failed to process order from SQS: orderId={}, error={}",
                    orderMessage.orderId(), e.getMessage(), e);
            // Do NOT delete — message becomes visible again after visibility timeout
        }
    }

    private void deleteMessage(Message message) {
        try {
            sqsAsyncClient.deleteMessage(r -> r
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
            ).get();
        } catch (Exception e) {
            log.error("Failed to delete SQS message: messageId={}, error={}",
                    message.messageId(), e.getMessage(), e);
        }
    }
}
