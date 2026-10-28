package com.nequi.ticketing.infrastructure.messaging.sqs;

import com.nequi.ticketing.application.order.ProcessPlacedOrderUseCase;
import com.nequi.ticketing.domain.model.OrderId;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

/**
 * Consumes placed orders. The message is deleted only when the returned future completes; a technical failure
 * leaves it on the queue to be redelivered, and after three receives SQS moves it to the DLQ. Malformed ids
 * fail on every attempt and therefore end up in the DLQ as well.
 */
@Profile("worker")
@Component
class PlacedOrderListener {

    private static final Logger log = LoggerFactory.getLogger(PlacedOrderListener.class);

    private final ProcessPlacedOrderUseCase processPlacedOrder;
    private final MeterRegistry meters;

    PlacedOrderListener(ProcessPlacedOrderUseCase processPlacedOrder, MeterRegistry meters) {
        this.processPlacedOrder = processPlacedOrder;
        this.meters = meters;
    }

    @SqsListener("${ticketing.worker.orders-queue}")
    CompletableFuture<Void> onOrderPlaced(String orderId) {
        return Mono.fromCallable(() -> new OrderId(orderId))
                .flatMap(processPlacedOrder::execute)
                .doOnNext(outcome -> {
                    meters.counter("ticketing.orders.processed", "outcome", outcome.name()).increment();
                    log.info("Order {} processed: {}", orderId, outcome);
                })
                .doOnError(error -> log.warn("Order {} processing failed, will be redelivered: {}", orderId, error.getMessage()))
                .then()
                .toFuture();
    }
}
