package com.nequi.consumerservice.infrastructure.messaging.sqs;

/**
 * Deserialized SQS message payload for ORDER_PLACED events.
 *
 * <p>This record maps to the JSON written by order-service in the outbox payload:
 * <pre>
 * {
 *   "orderId": "...",
 *   "reservationId": "...",
 *   "eventId": "...",
 *   "userId": "...",
 *   "seatsCount": 2
 * }
 * </pre>
 *
 * <p>Jackson deserializes the SQS message body into this record automatically
 * via Spring Cloud AWS {@code @SqsListener} message conversion.
 */
public record PurchaseOrderMessage(
        String orderId,
        String reservationId,
        String eventId,
        String userId,
        int seatsCount
) {}
