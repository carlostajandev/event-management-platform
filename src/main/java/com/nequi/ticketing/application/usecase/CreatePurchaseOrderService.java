package com.nequi.ticketing.application.usecase;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.nequi.ticketing.application.dto.CreatePurchaseOrderCommand;
import com.nequi.ticketing.application.dto.OrderResponse;
import com.nequi.ticketing.application.port.in.CreatePurchaseOrderUseCase;
import com.nequi.ticketing.application.port.out.MessagePublisher;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Creates a purchase order and enqueues it for async processing.
 *
 * <p>Flow:
 * <ol>
 *   <li>Build Order domain entity with PENDING status</li>
 *   <li>Persist order in DynamoDB</li>
 *   <li>Publish message to SQS purchase-orders queue</li>
 *   <li>Return immediately with orderId (HTTP 202)</li>
 * </ol>
 *
 * <p>The heavy processing (payment validation, ticket state update)
 * happens asynchronously in {@link ProcessOrderService}.
 */
@Service
public class CreatePurchaseOrderService implements CreatePurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePurchaseOrderService.class);

    private final OrderRepository orderRepository;
    private final MessagePublisher messagePublisher;
    private final JsonMapper objectMapper;

    public CreatePurchaseOrderService(
            OrderRepository orderRepository,
            MessagePublisher messagePublisher,
            JsonMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.messagePublisher = messagePublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<OrderResponse> execute(CreatePurchaseOrderCommand command) {
        log.debug("Creating purchase order: orderId={}, eventId={}, userId={}",
                command.orderId(), command.eventId(), command.userId());

        Order order = Order.create(
                OrderId.of(command.orderId()),
                EventId.of(command.eventId()),
                command.userId(),
                command.ticketIds(),
                command.ticketIds().size(),
                Money.of(command.totalAmount(), command.currency())
        );

        return orderRepository.save(order)
                .flatMap(saved -> publishToQueue(saved)
                        .thenReturn(saved))
                .map(OrderResponse::from)
                .doOnSuccess(r -> log.info(
                        "Purchase order created and enqueued: orderId={}", r.orderId()));
    }

    private Mono<String> publishToQueue(Order order) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(
                        new OrderMessage(
                                order.orderId().value(),
                                order.eventId().value(),
                                order.userId(),
                                order.ticketIds(),
                                order.totalAmount().amount().toPlainString(),
                                order.totalAmount().currency()
                        )))
                .onErrorMap(JacksonException.class,
                        ex -> new RuntimeException("Failed to serialize order message", ex))
                .flatMap(json -> messagePublisher
                        .publishPurchaseOrder(json, order.eventId().value()))
                .doOnSuccess(msgId -> log.debug(
                        "Order enqueued: orderId={}, messageId={}",
                        order.orderId().value(), msgId));
    }

    /**
     * SQS message payload — what the consumer will receive.
     */
    public record OrderMessage(
            String orderId,
            String eventId,
            String userId,
            java.util.List<String> ticketIds,
            String totalAmount,
            String currency
    ) {}
}