package com.nequi.shared.domain.exception;

public class EventNotFoundException extends NotFoundException {

    public EventNotFoundException(String eventId) {
        super("EVENT_NOT_FOUND", "Event not found: " + eventId);
    }
}
