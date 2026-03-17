package com.nequi.reservationservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-reservations} table.
 *
 * <p>Access pattern table:
 * <pre>
 *   Table: emp-reservations    Billing: PAY_PER_REQUEST
 *   PK (S): RESERVATION#id    SK (S): USER#userId
 *
 *   GSI1: GSI1PK (S) / GSI1SK (S)
 *     GSI1PK = STATUS#ACTIVE          — groups all active reservations
 *     GSI1SK = expiresAt (ISO-8601)   — range key enables range query: expiresAt <= now
 *
 *   TTL attribute: ttl (Number, epoch seconds) — DynamoDB auto-deletes expired items
 * </pre>
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>SK includes userId to support future query: find all reservations by user on an event</li>
 *   <li>GSI1 enables O(results) expiry sweep — NOT a full table scan</li>
 *   <li>Monetary values stored as String to avoid floating-point precision loss</li>
 *   <li>Instants stored as ISO-8601 String for human readability in the console</li>
 *   <li>Native DynamoDB TTL on {@code ttl} attribute — zero compute cost for expiry</li>
 * </ul>
 */
@DynamoDbBean
public class ReservationEntity {

    // ── Primary key ───────────────────────────────────────────────────────────
    private String pk;          // "RESERVATION#<id>"
    private String sk;          // "USER#<userId>"

    // ── GSI1 — query expired active reservations ───────────────────────────
    private String gsi1Pk;      // "STATUS#<status>" e.g. "STATUS#ACTIVE"
    private String gsi1Sk;      // expiresAt as ISO-8601 string (range key)

    // ── Business attributes ───────────────────────────────────────────────────
    private String id;
    private String eventId;
    private String userId;
    private int    seatsCount;
    private String totalAmount;     // BigDecimal.toPlainString()
    private String currency;        // ISO 4217 (e.g. "COP", "USD")
    private String status;          // ReservationStatus.name()
    private String expiresAt;       // ISO-8601 Instant
    private long   ttl;             // epoch seconds — DynamoDB native TTL attribute
    private String createdAt;       // ISO-8601 Instant
    private String updatedAt;       // ISO-8601 Instant

    // ── Constructors ──────────────────────────────────────────────────────────

    public ReservationEntity() {
        // DynamoDB Enhanced Client requires a no-arg constructor
    }

    // ── Partition / sort keys ─────────────────────────────────────────────────

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    // ── GSI1 ──────────────────────────────────────────────────────────────────

    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1PK")
    public String getGsi1Pk() { return gsi1Pk; }
    public void setGsi1Pk(String gsi1Pk) { this.gsi1Pk = gsi1Pk; }

    @DynamoDbSecondarySortKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1SK")
    public String getGsi1Sk() { return gsi1Sk; }
    public void setGsi1Sk(String gsi1Sk) { this.gsi1Sk = gsi1Sk; }

    // ── Business fields ───────────────────────────────────────────────────────

    @DynamoDbAttribute("id")
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @DynamoDbAttribute("eventId")
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    @DynamoDbAttribute("userId")
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @DynamoDbAttribute("seatsCount")
    public int getSeatsCount() { return seatsCount; }
    public void setSeatsCount(int seatsCount) { this.seatsCount = seatsCount; }

    @DynamoDbAttribute("totalAmount")
    public String getTotalAmount() { return totalAmount; }
    public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }

    @DynamoDbAttribute("currency")
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @DynamoDbAttribute("status")
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @DynamoDbAttribute("expiresAt")
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    @DynamoDbAttribute("ttl")
    public long getTtl() { return ttl; }
    public void setTtl(long ttl) { this.ttl = ttl; }

    @DynamoDbAttribute("createdAt")
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @DynamoDbAttribute("updatedAt")
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
