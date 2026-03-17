package com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-audit} table.
 * Used by consumer-service to write audit entries for order/reservation confirmations.
 *
 * <p>PK: AUDIT#entityId  SK: TIMESTAMP#iso-timestamp   TTL: 90 days
 */
@DynamoDbBean
public class AuditEntity {

    private String pk;
    private String sk;
    private String entityId;
    private String entityType;
    private String fromStatus;
    private String toStatus;
    private String userId;
    private String correlationId;
    private String timestamp;
    private long   ttl;

    public AuditEntity() {}

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

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
