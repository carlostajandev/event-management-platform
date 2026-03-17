package com.nequi.shared.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reservation aggregate — Java 25 Record.
 *
 * <p>TTL-based expiry: the {@code ttl} field is set to {@code now + 10 minutes}
 * in epoch seconds. DynamoDB deletes the item automatically when TTL expires —
 * no scheduler needed. This is O(1) vs O(table) for a scheduler-based approach
 * and scales to millions of concurrent reservations at zero compute cost.
 *
 * <p>DynamoDB Streams captures the TTL DELETE event. The consumer-service listens
 * to the stream and increments the event's availableCount atomically.
 * In local dev (LocalStack), a reconciliation @Scheduled job compensates for
 * DynamoDB Streams limitations in LocalStack.
 */
public record Reservation(
        String id,
        String eventId,
        String userId,
        int seatsCount,
        BigDecimal totalAmount,
        String currency,
        ReservationStatus status,
        Instant expiresAt,
        long ttl,               // epoch seconds — DynamoDB native TTL attribute
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Creates a new reservation with a 10-minute TTL.
     * The caller must persist this and perform the atomic counter decrement
     * on the event in the same TransactWriteItems call.
     */
    /**
     * Factory — caller supplies {@code now} from an injected {@link java.time.Clock}
     * so that time-sensitive tests remain deterministic.
     */
    public static Reservation create(
            String id, String eventId, String userId,
            int seatsCount, BigDecimal totalAmount, String currency,
            Instant now) {
        Instant expiresAt = now.plusSeconds(600); // 10 minutes
        return new Reservation(
                id, eventId, userId, seatsCount, totalAmount, currency,
                ReservationStatus.ACTIVE,
                expiresAt,
                expiresAt.getEpochSecond(),
                now, now
        );
    }

    public Reservation confirm() {
        return transition(ReservationStatus.CONFIRMED);
    }

    public Reservation cancel() {
        return transition(ReservationStatus.CANCELLED);
    }

    public Reservation expire() {
        return transition(ReservationStatus.EXPIRED);
    }

    /** Use injected clock: {@code isExpired(Instant.now(clock))} */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt) || status == ReservationStatus.EXPIRED;
    }

    private Reservation transition(ReservationStatus newStatus) {
        return new Reservation(id, eventId, userId, seatsCount, totalAmount, currency,
                newStatus, expiresAt, ttl, createdAt, Instant.now());
    }
}