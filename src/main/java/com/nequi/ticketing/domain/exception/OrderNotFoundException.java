package com.nequi.ticketing.domain.exception;

import com.nequi.ticketing.domain.valueobject.OrderId;

/**
 * Thrown when an order is not found by its identifier.
 * Mapped to HTTP 404 by GlobalErrorHandler.
 */
public class OrderNotFoundException extends RuntimeException {

    private final OrderId orderId;

    public OrderNotFoundException(OrderId orderId) {
        super("Order not found: " + orderId.value());
        this.orderId = orderId;
    }

    public OrderId getOrderId() {
        return orderId;
    }
}
