package com.nequi.ticketing.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * DynamoDB entity for idempotency key storage.
 * TTL is managed by DynamoDB's native TTL feature (expiresAt attribute).
 */
@DynamoDbBean
public class IdempotencyDynamoDbEntity {

    private String idempotencyKey;
    private String responseJson;
    private String createdAt;
    private Long expiresAt; // Unix epoch seconds — DynamoDB TTL

    @DynamoDbPartitionKey
    @DynamoDbAttribute("idempotencyKey")
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    @DynamoDbAttribute("responseJson")
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    @DynamoDbAttribute("createdAt")
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @DynamoDbAttribute("expiresAt")
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
