package com.nequi.shared.domain.exception;

public class OrderNotFoundException extends NotFoundException {

    public OrderNotFoundException(String orderId) {
        super("ORDER_NOT_FOUND", "Order not found: " + orderId);
    }
}
