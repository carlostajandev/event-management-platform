package com.nequi.orderservice.application.usecase;

import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.orderservice.application.mapper.OrderMapper;
import com.nequi.orderservice.application.port.in.GetOrderStatusUseCase;
import com.nequi.shared.domain.exception.OrderNotFoundException;
import com.nequi.shared.domain.port.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service implementing the get-order-status use case.
 *
 * <p>Simple read path: lookup by orderId → 404 if not found → map to response DTO
 * via {@link OrderMapper}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetOrderStatusService implements GetOrderStatusUseCase {

    private final OrderRepository orderRepository;
    private final OrderMapper     orderMapper;

    @Override
    public Mono<OrderResponse> execute(String orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
                .map(orderMapper::toResponse)
                .doOnSuccess(r -> log.debug("Order status retrieved: orderId={}, status={}", orderId, r.status()))
                .doOnError(OrderNotFoundException.class, ex ->
                        log.warn("Order not found: orderId={}", orderId));
    }
}
