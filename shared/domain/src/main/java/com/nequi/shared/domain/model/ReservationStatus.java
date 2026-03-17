package com.nequi.shared.domain.model;

public enum ReservationStatus {
    ACTIVE,      // reserved, awaiting payment — TTL running
    CONFIRMED,   // order processed, payment confirmed
    CANCELLED,   // user cancelled before TTL
    EXPIRED      // TTL elapsed, tickets released back to event
}