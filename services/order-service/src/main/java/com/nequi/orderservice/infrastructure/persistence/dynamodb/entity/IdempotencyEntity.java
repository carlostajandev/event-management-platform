package com.nequi.orderservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-idempotency-keys} table.
 *
 * <p>Access pattern table:
 * <pre>
 *   Table: emp-idempotency-keys   Billing: PAY_PER_REQUEST
 *   PK (S): KEY#&lt;idempotency-uuid&gt;  SK (S): IDEMPOTENCY
 *   TTL attribute: ttl (Number, epoch seconds) — auto-deleted after 24h
 * </pre>
 *
 * <p>The fixed SK value {@code "IDEMPOTENCY"} allows point-lookup by PK only,
 * while keeping the table schema consistent with the composite key design used
 * across all emp-* tables.
 */
@DynamoDbBean
public class IdempotencyEntity {

    // ── Primary key ───────────────────────────────────────────────────────────
    private String pk;      // "KEY#<idempotency-uuid>"
    private String sk;      // "IDEMPOTENCY"

    // ── Business fields ───────────────────────────────────────────────────────
    private String key;
    private String orderId;
    private String cachedResponseJson;  // serialized OrderResponse
    private String createdAt;           // ISO-8601 Instant
    private long   ttl;                 // epoch seconds — 24h TTL

    // ── Constructors ──────────────────────────────────────────────────────────

    public IdempotencyEntity() {
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

    @DynamoDbAttribute("key")
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    @DynamoDbAttribute("orderId")
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    @DynamoDbAttribute("cachedResponseJson")
    public String getCachedResponseJson() { return cachedResponseJson; }
    public void setCachedResponseJson(String cachedResponseJson) { this.cachedResponseJson = cachedResponseJson; }

    @DynamoDbAttribute("createdAt")
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @DynamoDbAttribute("ttl")
    public long getTtl() { return ttl; }
    public void setTtl(long ttl) { this.ttl = ttl; }
}
