package com.nequi.eventservice.application;

/**
 * Centralized constants for event-service application layer.
 * Eliminates magic strings and numbers scattered across use case classes.
 */
public final class EventConstants {

    // ── Micrometer metric names ──────────────────────────────────────────────
    public static final String METRIC_EVENTS_CREATED      = "events.created.total";
    public static final String GAUGE_AVAILABLE_TICKETS    = "event.available_tickets";

    // ── Audit fields ─────────────────────────────────────────────────────────
    public static final String AUDIT_ENTITY_TYPE          = "EVENT";

    private EventConstants() {}
}
