package com.nequi.eventservice.infrastructure.persistence.dynamodb.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

/**
 * DynamoDB Enhanced Client entity mapping for the {@code emp-events} table.
 *
 * <p>Access pattern table:
 * <pre>
 *   PK               SK           GSI1PK
 *   EVENT#<id>       METADATA     STATUS#<status>
 * </pre>
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>PK = "EVENT#" + id — prevents hot-key collisions with other entity types</li>
 *   <li>SK = "METADATA" — constant allows future sort-key range queries (e.g. SHARD#n)</li>
 *   <li>GSI1 on GSI1PK — supports {@code findByStatus} without full scan</li>
 *   <li>{@code version} uses {@code @DynamoDbVersionAttribute} for optimistic locking;
 *       the raw conditional-write for ticket reservation uses the low-level client instead</li>
 *   <li>Monetary values stored as String to avoid floating-point precision loss</li>
 *   <li>Instants stored as ISO-8601 String for human readability in the console</li>
 * </ul>
 */
@DynamoDbBean
public class EventEntity {

    // ── Primary key ───────────────────────────────────────────────────────────
    private String pk;          // "EVENT#<id>"
    private String sk;          // "METADATA"

    // ── GSI1 — list by status ─────────────────────────────────────────────────
    private String gsi1Pk;      // "STATUS#<status>"

    // ── Business attributes ───────────────────────────────────────────────────
    private String id;
    private String name;
    private String description;

    // Venue (denormalized — no separate table for a value object)
    private String venueName;
    private String venueAddress;
    private String venueCity;
    private String venueCountry;
    private int    venueCapacity;

    // Monetary / temporal fields stored as String to preserve precision & readability
    private String eventDate;       // ISO-8601 Instant
    private String ticketPrice;     // BigDecimal.toPlainString()
    private String currency;        // ISO 4217 (e.g. "COP", "USD")
    private String status;          // EventStatus.name()
    private String createdAt;       // ISO-8601 Instant
    private String updatedAt;       // ISO-8601 Instant

    // ── Inventory ─────────────────────────────────────────────────────────────
    private int  totalCapacity;
    private int  availableCount;    // atomic counter (ADD expression for release)

    // ── Optimistic locking ────────────────────────────────────────────────────
    private Long version;

    // ── Constructors ─────────────────────────────────────────────────────────
    public EventEntity() {
        // DynamoDB Enhanced Client requires a no-arg constructor
    }

    // ── Partition / sort keys ────────────────────────────────────────────────

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    // ── GSI1 ─────────────────────────────────────────────────────────────────

    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1PK")
    public String getGsi1Pk() { return gsi1Pk; }
    public void setGsi1Pk(String gsi1Pk) { this.gsi1Pk = gsi1Pk; }

    // ── Business fields ───────────────────────────────────────────────────────

    @DynamoDbAttribute("id")
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @DynamoDbAttribute("name")
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @DynamoDbAttribute("description")
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @DynamoDbAttribute("venueName")
    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }

    @DynamoDbAttribute("venueAddress")
    public String getVenueAddress() { return venueAddress; }
    public void setVenueAddress(String venueAddress) { this.venueAddress = venueAddress; }

    @DynamoDbAttribute("venueCity")
    public String getVenueCity() { return venueCity; }
    public void setVenueCity(String venueCity) { this.venueCity = venueCity; }

    @DynamoDbAttribute("venueCountry")
    public String getVenueCountry() { return venueCountry; }
    public void setVenueCountry(String venueCountry) { this.venueCountry = venueCountry; }

    @DynamoDbAttribute("venueCapacity")
    public int getVenueCapacity() { return venueCapacity; }
    public void setVenueCapacity(int venueCapacity) { this.venueCapacity = venueCapacity; }

    @DynamoDbAttribute("eventDate")
    public String getEventDate() { return eventDate; }
    public void setEventDate(String eventDate) { this.eventDate = eventDate; }

    @DynamoDbAttribute("ticketPrice")
    public String getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(String ticketPrice) { this.ticketPrice = ticketPrice; }

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

    @DynamoDbAttribute("totalCapacity")
    public int getTotalCapacity() { return totalCapacity; }
    public void setTotalCapacity(int totalCapacity) { this.totalCapacity = totalCapacity; }

    @DynamoDbAttribute("availableCount")
    public int getAvailableCount() { return availableCount; }
    public void setAvailableCount(int availableCount) { this.availableCount = availableCount; }

    @DynamoDbVersionAttribute
    @DynamoDbAttribute("version")
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}