package com.nequi.consumerservice.application.usecase;

import com.nequi.shared.domain.model.OutboxMessage;
import com.nequi.shared.domain.port.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Application service that polls the DynamoDB outbox table and publishes
 * unprocessed messages to SQS.
 *
 * <p>This service implements the Outbox Pattern delivery side. The order-service
 * writes messages to {@code emp-outbox} atomically with orders. This poller reads
 * unpublished messages (GSI1PK = "PUBLISHED#false") every 5 seconds and forwards
 * them to SQS.
 *
 * <p>Failure handling: if publishing a single message fails, {@code onErrorResume}
 * skips that message and continues with the rest. The message remains unpublished
 * (GSI1PK stays "PUBLISHED#false") and will be retried in the next poll cycle.
 * This is true at-least-once delivery with per-message retry isolation.
 *
 * <p>Called by {@link com.nequi.consumerservice.infrastructure.scheduler.OutboxPoller}
 * every 5 seconds ({@code fixedDelay} — not {@code fixedRate} — to prevent overlapping
 * poll cycles under load).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPollerService {

    private final OutboxRepository outboxRepository;
    private final SqsAsyncClient   sqsAsyncClient;
    private final ObjectMapper      objectMapper;

    @Value("${aws.sqs.queue.purchase-orders}")
    private String queueUrl;

    /**
     * Polls for unpublished outbox messages and publishes them to SQS.
     *
     * <p>Each message is processed independently: a failed publish skips that
     * message (via {@code onErrorResume}) so the rest of the batch still completes.
     *
     * @return a {@link Mono} that completes when the poll cycle finishes
     */
    public Mono<Void> pollAndPublish() {
        return outboxRepository.findUnpublished()
                .flatMap(msg -> publishToSqs(msg)
                        .then(Mono.defer(() -> outboxRepository.markPublished(msg.id())))
                        .doOnSuccess(v -> log.info("Published and marked outbox message: id={}, eventType={}, aggregateId={}",
                                msg.id(), msg.eventType(), msg.aggregateId()))
                        .doOnError(e -> log.error("Failed to publish outbox message: id={}, error={}",
                                msg.id(), e.getMessage(), e))
                        .onErrorResume(e -> Mono.empty()) // skip failed, retry in next cycle
                )
                .then()
                .doOnError(e -> log.error("Outbox poll cycle error: {}", e.getMessage(), e));
    }

    // ── SQS publish ───────────────────────────────────────────────────────────

    /**
     * Publishes a single outbox message to SQS.
     *
     * <p>The message body is the raw {@code payload} field from the outbox record —
     * already serialized JSON by the order-service. Uses standard queue (not FIFO)
     * so no {@code MessageGroupId} is required.
     *
     * @param msg the outbox message to publish
     * @return a {@link Mono} that completes when SQS acknowledges the send
     */
    private Mono<Void> publishToSqs(OutboxMessage msg) {
        SendMessageRequest sendRequest = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(msg.payload())
                .messageAttributes(java.util.Map.of(
                        "eventType", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(msg.eventType())
                                .build(),
                        "aggregateId", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(msg.aggregateId())
                                .build()
                ))
                .build();

        return Mono.fromFuture(sqsAsyncClient.sendMessage(sendRequest))
                .doOnSuccess(resp -> log.debug("SQS message sent: messageId={}, outboxId={}",
                        resp.messageId(), msg.id()))
                .then();
    }
}
