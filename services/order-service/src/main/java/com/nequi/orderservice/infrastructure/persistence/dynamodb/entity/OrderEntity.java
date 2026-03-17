package com.nequi.orderservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-orders} table.
 *
 * <p>Access pattern table:
 * <pre>
 *   Table: emp-orders         Billing: PAY_PER_REQUEST
 *   PK (S): ORDER#orderId     SK (S): RESERVATION#reservationId
 *
 *   GSI1: GSI1PK (S) / GSI1SK (S)
 *     GSI1PK = USER#userId    — enables "find all orders by user" history query
 *     GSI1SK = createdAt (ISO-8601, sortable)
 * </pre>
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>SK includes reservationId to enforce uniqueness: one order per reservation</li>
 *   <li>GSI1 enables pageable user order history without full-table scan</li>
 *   <li>Monetary values stored as String to avoid floating-point precision loss</li>
 *   <li>idempotencyKey stored as attribute for audit trail and deduplication evidence</li>
 * </ul>
 */
@DynamoDbBean
public class OrderEntity {

    // ── Primary key ───────────────────────────────────────────────────────────
    private String pk;          // "ORDER#<orderId>"
    private String sk;          // "RESERVATION#<reservationId>"

    // ── GSI1 — user order history ─────────────────────────────────────────────
    private String gsi1Pk;      // "USER#<userId>"
    private String gsi1Sk;      // createdAt as ISO-8601 string (range key)

    // ── Business attributes ───────────────────────────────────────────────────
    private String id;
    private String reservationId;
    private String eventId;
    private String userId;
    private int    seatsCount;
    private String totalAmount;     // BigDecimal.toPlainString()
    private String currency;        // ISO 4217 (e.g. "COP", "USD")
    private String status;          // OrderStatus.name()
    private String idempotencyKey;  // client-supplied UUID for deduplication
    private String createdAt;       // ISO-8601 Instant
    private String updatedAt;       // ISO-8601 Instant

    // ── Constructors ──────────────────────────────────────────────────────────

    public OrderEntity() {
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

    @DynamoDbAttribute("reservationId")
    public String getReservationId() { return reservationId; }
    public void setReservationId(String reservationId) { this.reservationId = reservationId; }

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

    @DynamoDbAttribute("idempotencyKey")
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    @DynamoDbAttribute("createdAt")
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @DynamoDbAttribute("updatedAt")
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
