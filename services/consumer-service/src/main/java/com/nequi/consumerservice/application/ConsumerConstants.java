package com.nequi.consumerservice.application;

/**
 * Centralized constants for consumer-service application layer.
 * Eliminates magic strings and numbers scattered across use case classes.
 */
public final class ConsumerConstants {

    // ── Micrometer metric names ──────────────────────────────────────────────
    public static final String METRIC_ORDERS_PROCESSED        = "orders.processed.total";
    public static final String METRIC_ORDER_PROCESSING_TIMER  = "order.processing.duration";

    // ── Audit fields ─────────────────────────────────────────────────────────
    public static final String AUDIT_ENTITY_ORDER             = "ORDER";
    public static final String AUDIT_ENTITY_RESERVATION       = "RESERVATION";
    public static final String AUDIT_STATUS_PENDING           = "PENDING_CONFIRMATION";
    public static final String AUDIT_STATUS_CONFIRMED         = "CONFIRMED";
    public static final String AUDIT_STATUS_ACTIVE            = "ACTIVE";

    private ConsumerConstants() {}
}
