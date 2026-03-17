package com.nequi.orderservice.application;

/**
 * Centralized constants for order-service application layer.
 * Eliminates magic strings and numbers scattered across use case classes.
 */
public final class OrderConstants {

    // ── Micrometer metric names ──────────────────────────────────────────────
    public static final String METRIC_ORDERS_CREATED      = "orders.created.total";

    // ── Audit fields ─────────────────────────────────────────────────────────
    public static final String AUDIT_ENTITY_TYPE          = "ORDER";
    public static final String AUDIT_STATUS_NONE          = "NONE";
    public static final String AUDIT_STATUS_PENDING       = "PENDING_CONFIRMATION";

    // ── Outbox ───────────────────────────────────────────────────────────────
    public static final String OUTBOX_EVENT_TYPE          = "ORDER_PLACED";

    private OrderConstants() {}
}
