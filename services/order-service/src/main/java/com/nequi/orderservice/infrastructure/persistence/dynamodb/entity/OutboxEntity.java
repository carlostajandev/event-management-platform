package com.nequi.orderservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-outbox} table.
 *
 * <p>Access pattern table:
 * <pre>
 *   Table: emp-outbox             Billing: PAY_PER_REQUEST
 *   PK (S): OUTBOX#messageId      SK (S): CREATED_AT#iso-timestamp
 *   TTL attribute: ttl (Number, epoch seconds) — auto-deleted after 24h
 *
 *   GSI1: GSI1PK (S) — no sort key needed
 *     GSI1PK = PUBLISHED#false   — OutboxPoller queries this to find unprocessed messages
 *     After publishing: GSI1PK updated to PUBLISHED#true (removes from poller's view)
 * </pre>
 *
 * <p>The GSI design ensures the OutboxPoller only reads unpublished messages
 * in O(unpublished) — not O(total outbox table size).
 */
@DynamoDbBean
public class OutboxEntity {

    // ── Primary key ───────────────────────────────────────────────────────────
    private String pk;          // "OUTBOX#<id>"
    private String sk;          // "CREATED_AT#<iso-timestamp>"

    // ── GSI1 — unpublished messages for OutboxPoller ───────────────────────
    private String gsi1Pk;      // "PUBLISHED#false" or "PUBLISHED#true"

    // ── Business fields ───────────────────────────────────────────────────────
    private String id;
    private String aggregateId;     // orderId
    private String aggregateType;   // "ORDER"
    private String eventType;       // "ORDER_PLACED"
    private String payload;         // JSON string
    private boolean published;
    private String createdAt;       // ISO-8601 Instant
    private long   ttl;             // epoch seconds — 24h TTL

    // ── Constructors ──────────────────────────────────────────────────────────

    public OutboxEntity() {
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

    // ── Business fields ───────────────────────────────────────────────────────

    @DynamoDbAttribute("id")
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @DynamoDbAttribute("aggregateId")
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    @DynamoDbAttribute("aggregateType")
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }

    @DynamoDbAttribute("eventType")
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    @DynamoDbAttribute("payload")
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    @DynamoDbAttribute("published")
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    @DynamoDbAttribute("createdAt")
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @DynamoDbAttribute("ttl")
    public long getTtl() { return ttl; }
    public void setTtl(long ttl) { this.ttl = ttl; }
}
