package com.nequi.ticketing.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;

/**
 * DynamoDB persistence entity for Ticket.
 *
 * <p>GSI: eventId-status-index
 * Partition key: eventId | Sort key: status
 * Enables efficient queries like "find all AVAILABLE tickets for event X"
 * without full table scans.
 */
@DynamoDbBean
public class TicketDynamoDbEntity {

    private String ticketId;
    private String eventId;
    private String userId;
    private String orderId;
    private String status;
    private BigDecimal price;
    private String currency;
    private String reservedAt;
    private String expiresAt;
    private String confirmedAt;
    private String createdAt;
    private String updatedAt;
    private Long version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("ticketId")
    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "eventId-status-index")
    @DynamoDbAttribute("eventId")
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    @DynamoDbAttribute("userId")
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @DynamoDbAttribute("orderId")
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    @DynamoDbSecondarySortKey(indexNames = "eventId-status-index")
    @DynamoDbAttribute("status")
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @DynamoDbAttribute("price")
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    @DynamoDbAttribute("currency")
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @DynamoDbAttribute("reservedAt")
    public String getReservedAt() { return reservedAt; }
    public void setReservedAt(String reservedAt) { this.reservedAt = reservedAt; }

    @DynamoDbAttribute("expiresAt")
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    @DynamoDbAttribute("confirmedAt")
    public String getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }

    @DynamoDbAttribute("createdAt")
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @DynamoDbAttribute("updatedAt")
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    @DynamoDbAttribute("version")
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
