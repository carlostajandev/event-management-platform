package com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-orders} table.
 * Used by consumer-service to read and update order records.
 *
 * <p>Access pattern:
 * <pre>
 *   PK (S): ORDER#orderId     SK (S): RESERVATION#reservationId
 *   GSI1PK (S): USER#userId   GSI1SK (S): createdAt (ISO-8601)
 * </pre>
 */
@DynamoDbBean
public class OrderEntity {

    private String pk;
    private String sk;
    private String gsi1Pk;
    private String gsi1Sk;
    private String id;
    private String reservationId;
    private String eventId;
    private String userId;
    private int    seatsCount;
    private String totalAmount;
    private String currency;
    private String status;
    private String idempotencyKey;
    private String createdAt;
    private String updatedAt;

    public OrderEntity() {}

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1PK")
    public String getGsi1Pk() { return gsi1Pk; }
    public void setGsi1Pk(String gsi1Pk) { this.gsi1Pk = gsi1Pk; }

    @DynamoDbSecondarySortKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1SK")
    public String getGsi1Sk() { return gsi1Sk; }
    public void setGsi1Sk(String gsi1Sk) { this.gsi1Sk = gsi1Sk; }

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
