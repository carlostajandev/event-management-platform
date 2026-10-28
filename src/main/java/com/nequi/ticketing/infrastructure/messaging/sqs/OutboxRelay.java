package com.nequi.ticketing.infrastructure.messaging.sqs;

import com.nequi.ticketing.infrastructure.config.WorkerProperties;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoDbOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Publishes outbox messages to SQS, then marks them published. A crash between both steps re-sends the message
 * on the next run: delivery is at least once and the consumer is idempotent. In AWS this poller is replaced by
 * DynamoDB Streams + EventBridge Pipes (ADR 0001).
 */
@Profile("worker")
@Component
class OutboxRelay {

    static final String MESSAGE_TYPE = "messageType";
    static final String EVENT_ID = "eventId";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final DynamoDbOutbox outbox;
    private final SqsAsyncClient sqs;
    private final WorkerProperties properties;
    private final Clock clock;
    private final Mono<String> queueUrl;

    OutboxRelay(DynamoDbOutbox outbox, SqsAsyncClient sqs, WorkerProperties properties, Clock clock) {
        this.outbox = outbox;
        this.sqs = sqs;
        this.properties = properties;
        this.clock = clock;
        this.queueUrl = Mono.fromFuture(() -> sqs.getQueueUrl(request -> request.queueName(properties.ordersQueue())))
                .map(response -> response.queueUrl())
                // Resolved once; a failed lookup is not cached so the next run retries it.
                .cache(url -> Duration.ofDays(365), error -> Duration.ZERO, () -> Duration.ZERO);
    }

    @Scheduled(fixedDelayString = "${ticketing.worker.outbox-poll-interval}")
    Mono<Void> relay() {
        return outbox.pending(properties.outboxBatchSize())
                .concatMap(message -> publish(message)
                        .then(outbox.markPublished(message, clock.instant()))
                        .onErrorResume(error -> {
                            log.warn("Outbox message {} not published yet: {}", message.id(), error.getMessage());
                            return Mono.empty();
                        }))
                .onErrorResume(error -> {
                    log.warn("Outbox relay run failed: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> publish(DynamoDbOutbox.Message message) {
        return queueUrl.flatMap(url -> Mono.fromFuture(() -> sqs.sendMessage(request -> request
                        .queueUrl(url)
                        .messageBody(message.orderId())
                        .messageAttributes(Map.of(
                                MESSAGE_TYPE, stringAttribute(message.type()),
                                EVENT_ID, stringAttribute(message.eventId()))))))
                .then();
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
