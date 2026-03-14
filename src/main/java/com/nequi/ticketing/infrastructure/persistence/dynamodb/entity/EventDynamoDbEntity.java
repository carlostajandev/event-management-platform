package com.nequi.ticketing.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.math.BigDecimal;

/**
 * DynamoDB persistence entity for Event.
 *
 * <p>Optimistic locking is handled manually via ConditionExpression
 * in the repository — checking that version = expectedVersion before update.
 * This gives us full control over the conflict resolution strategy.
 */
@DynamoDbBean
public class EventDynamoDbEntity {

    private String eventId;
    private String name;
    private String description;
    private String eventDate;
    private String venueName;
    private String venueCity;
    private String venueCountry;
    private int totalCapacity;
    private int availableTickets;
    private BigDecimal ticketPrice;
    private String currency;
    private String status;
    private String createdAt;
    private String updatedAt;
    private Long version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("eventId")
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    @DynamoDbAttribute("name")
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @DynamoDbAttribute("description")
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @DynamoDbAttribute("eventDate")
    public String getEventDate() { return eventDate; }
    public void setEventDate(String eventDate) { this.eventDate = eventDate; }

    @DynamoDbAttribute("venueName")
    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }

    @DynamoDbAttribute("venueCity")
    public String getVenueCity() { return venueCity; }
    public void setVenueCity(String venueCity) { this.venueCity = venueCity; }

    @DynamoDbAttribute("venueCountry")
    public String getVenueCountry() { return venueCountry; }
    public void setVenueCountry(String venueCountry) { this.venueCountry = venueCountry; }

    @DynamoDbAttribute("totalCapacity")
    public int getTotalCapacity() { return totalCapacity; }
    public void setTotalCapacity(int totalCapacity) { this.totalCapacity = totalCapacity; }

    @DynamoDbAttribute("availableTickets")
    public int getAvailableTickets() { return availableTickets; }
    public void setAvailableTickets(int availableTickets) { this.availableTickets = availableTickets; }

    @DynamoDbAttribute("ticketPrice")
    public BigDecimal getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(BigDecimal ticketPrice) { this.ticketPrice = ticketPrice; }

    @DynamoDbAttribute("currency")
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    @DynamoDbAttribute("status")
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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