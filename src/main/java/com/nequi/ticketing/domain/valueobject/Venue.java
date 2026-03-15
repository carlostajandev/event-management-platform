package com.nequi.ticketing.domain.valueobject;

/**
 * Represents the physical location of an event.
 */
public record Venue(String name, String city, String country) {

    public Venue {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Venue name cannot be blank");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("Venue city cannot be blank");
        }
        if (country == null || country.isBlank()) {
            throw new IllegalArgumentException("Venue country cannot be blank");
        }
    }

    public static Venue of(String name, String city, String country) {
        return new Venue(name, city, country);
    }

    @Override
    public String toString() {
        return "%s, %s, %s".formatted(name, city, country);
    }
}
