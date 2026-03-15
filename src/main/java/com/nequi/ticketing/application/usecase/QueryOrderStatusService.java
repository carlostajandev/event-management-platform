package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.OrderResponse;
import com.nequi.ticketing.application.port.in.QueryOrderStatusUseCase;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.valueobject.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Returns the current status of a purchase order.
 *
 * <p>Used by GET /api/v1/orders/{orderId} to allow clients to poll
 * the result of an async purchase operation. Since order processing
 * is asynchronous (via SQS), clients need this endpoint to know
 * whether their order was confirmed, failed, or is still pending.
 */
@Service
public class QueryOrderStatusService implements QueryOrderStatusUseCase {

    private static final Logger log = LoggerFactory.getLogger(QueryOrderStatusService.class);

    private final OrderRepository orderRepository;

    public QueryOrderStatusService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public Mono<OrderResponse> findById(String orderId) {
        log.debug("Querying order status: orderId={}", orderId);

        OrderId id = OrderId.of(orderId);

        return orderRepository.findById(id)
                .switchIfEmpty(Mono.error(new OrderNotFoundException(id)))
                .map(OrderResponse::from)
                .doOnSuccess(r -> log.debug(
                        "Order status retrieved: orderId={}, status={}",
                        orderId, r.status()));
    }
}