package com.nequi.shared.infrastructure.util;

import java.time.Instant;

/**
 * Standard error response body — consistent across all 4 services.
 *
 * <p>{@code errorCode} is a machine-readable code safe to expose to clients
 * (e.g. "EVENT_NOT_FOUND"). {@code error} is the HTTP reason phrase.
 */
public record ErrorResponse(
        int status,
        String error,
        String errorCode,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(int status, String error, String errorCode, String message, String traceId) {
        return new ErrorResponse(status, error, errorCode, message, traceId, Instant.now());
    }
}