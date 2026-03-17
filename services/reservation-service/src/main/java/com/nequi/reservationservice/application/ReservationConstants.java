package com.nequi.reservationservice.application;

/**
 * Centralized constants for reservation-service application layer.
 * Eliminates magic strings and numbers scattered across use case classes.
 */
public final class ReservationConstants {

    // ── Business rules ───────────────────────────────────────────────────────
    public static final int MAX_SEATS_PER_RESERVATION     = 10;
    public static final int MAX_RETRY_ATTEMPTS            = 3;
    public static final long RETRY_BACKOFF_MILLIS         = 100L;

    // ── Micrometer metric names ──────────────────────────────────────────────
    public static final String METRIC_TICKETS_RESERVED    = "tickets.reserved.total";
    public static final String METRIC_RESERVATIONS_EXPIRED    = "reservation.expired.released";
    public static final String METRIC_RESERVATIONS_CANCELLED  = "reservations.cancelled.total";

    // ── Audit fields ─────────────────────────────────────────────────────────
    public static final String AUDIT_ENTITY_TYPE          = "RESERVATION";
    public static final String AUDIT_STATUS_NONE          = "NONE";
    public static final String AUDIT_STATUS_ACTIVE        = "ACTIVE";
    public static final String AUDIT_STATUS_EXPIRED       = "EXPIRED";
    public static final String AUDIT_STATUS_CANCELLED     = "CANCELLED";
    public static final String AUDIT_STATUS_CONFIRMED     = "CONFIRMED";

    // ── System identifiers ───────────────────────────────────────────────────
    public static final String SYSTEM_USER                = "system";
    public static final String SCHEDULER_CORRELATION      = "scheduler";

    private ReservationConstants() {}
}
