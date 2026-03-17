package com.nequi.shared.domain.model;

/**
 * Venue value object — embedded in Event, no separate table.
 */
public record Venue(
        String name,
        String address,
        String city,
        String country,
        int capacity
) {}