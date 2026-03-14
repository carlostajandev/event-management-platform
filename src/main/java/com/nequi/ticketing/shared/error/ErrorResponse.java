package com.nequi.ticketing.shared.error;

/**
 * Standard error response body returned by {@link GlobalErrorHandler}.
 *
 * <p>All error responses from the API follow this structure:
 * <pre>
 * {
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Event evt_abc123 not found",
 *   "path": "/api/v1/events/evt_abc123",
 *   "timestamp": "2026-03-13T10:30:00Z"
 * }
 * </pre>
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        String timestamp
) {}
