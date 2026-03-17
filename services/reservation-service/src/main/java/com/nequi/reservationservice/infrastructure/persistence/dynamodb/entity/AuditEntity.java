package com.nequi.reservationservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-audit} table.
 *
 * <p>Access pattern table:
 * <pre>
 *   Table: emp-audit          Billing: PAY_PER_REQUEST
 *   PK (S): AUDIT#entityId   SK (S): TIMESTAMP#iso-timestamp
 *   TTL attribute: ttl (Number, epoch seconds) — auto-deleted after 90 days
 * </pre>
 *
 * <p>The composite SK with timestamp prefix allows efficient range queries:
 * {@code findByEntityId} queries all audit entries for a given entity
 * ordered chronologically.
 */
@DynamoDbBean
public class AuditEntity {

    // ── Primary key ───────────────────────────────────────────────────────────
    private String pk;          // "AUDIT#<entityId>"
    private String sk;          // "TIMESTAMP#<iso-timestamp>"

    // ── Audit fields ──────────────────────────────────────────────────────────
    private String entityId;
    private String entityType;      // "RESERVATION", "ORDER"
    private String fromStatus;
    private String toStatus;
    private String userId;
    private String correlationId;
    private String timestamp;       // ISO-8601 Instant
    private long   ttl;             // epoch seconds — DynamoDB native TTL (90 days)

    // ── Constructors ──────────────────────────────────────────────────────────

    public AuditEntity() {
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

    // ── Business fields ───────────────────────────────────────────────────────

    @DynamoDbAttribute("entityId")
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    @DynamoDbAttribute("entityType")
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    @DynamoDbAttribute("fromStatus")
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }

    @DynamoDbAttribute("toStatus")
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }

    @DynamoDbAttribute("userId")
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @DynamoDbAttribute("correlationId")
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    @DynamoDbAttribute("timestamp")
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    @DynamoDbAttribute("ttl")
    public long getTtl() { return ttl; }
    public void setTtl(long ttl) { this.ttl = ttl; }
}
