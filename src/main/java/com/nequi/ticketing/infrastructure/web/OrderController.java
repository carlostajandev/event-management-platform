package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.order.ConfirmOrderUseCase;
import com.nequi.ticketing.application.order.GetOrderUseCase;
import com.nequi.ticketing.application.order.PlaceOrderCommand;
import com.nequi.ticketing.application.order.PlaceOrderUseCase;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.infrastructure.web.dto.OrderResponse;
import com.nequi.ticketing.infrastructure.web.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static com.nequi.ticketing.infrastructure.web.EventController.IDEMPOTENCY_KEY;

@Profile("api")
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final PlaceOrderUseCase placeOrder;
    private final GetOrderUseCase getOrder;
    private final ConfirmOrderUseCase confirmOrder;
    private final CurrentCaller caller;

    OrderController(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder, ConfirmOrderUseCase confirmOrder,
                    CurrentCaller caller) {
        this.placeOrder = placeOrder;
        this.getOrder = getOrder;
        this.confirmOrder = confirmOrder;
        this.caller = caller;
    }

    /** 202 Accepted: tickets are held and the order is queued for the consumer (RF3). */
    @PostMapping
    Mono<ResponseEntity<OrderResponse>> place(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                              @Valid @RequestBody PlaceOrderRequest request,
                                              ServerWebExchange exchange) {
        return caller.user(exchange)
                .map(user -> new PlaceOrderCommand(new EventId(request.eventId()), user, request.quantity(),
                        new IdempotencyKey(user.value(), idempotencyKey),
                        RequestFingerprint.of(request.eventId(), request.quantity())))
                .flatMap(placeOrder::execute)
                .map(order -> ResponseEntity.accepted()
                        .location(URI.create("/api/v1/orders/" + order.id().value()))
                        .body(OrderResponse.from(order)));
    }

    @GetMapping("/{id}")
    Mono<OrderResponse> get(@PathVariable String id, ServerWebExchange exchange) {
        return caller.user(exchange)
                .flatMap(user -> getOrder.execute(new OrderId(id), user))
                .map(OrderResponse::from);
    }

    @PostMapping("/{id}/confirm")
    Mono<OrderResponse> confirm(@PathVariable String id, ServerWebExchange exchange) {
        return caller.user(exchange)
                .flatMap(user -> confirmOrder.execute(new OrderId(id), user))
                .map(OrderResponse::from);
    }
}
