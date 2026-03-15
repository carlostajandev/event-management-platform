package com.nequi.ticketing.application.port.out;

import reactor.core.publisher.Mono;

/**
 * Driven port for publishing messages to a message queue.
 * Implemented by SqsMessagePublisher in the infrastructure layer.
 */
public interface MessagePublisher {

    /**
     * Publishes a message to the purchase orders queue.
     *
     * @param messageBody  serialized message content
     * @param messageGroupId optional grouping key (for FIFO queues)
     * @return message ID assigned by the queue
     */
    Mono<String> publishPurchaseOrder(String messageBody, String messageGroupId);
}
