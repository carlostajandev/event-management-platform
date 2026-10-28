package com.nequi.ticketing.domain.exception;

public final class NotFoundException extends DomainException {

    public NotFoundException(String resource, String id) {
        super("%s %s not found".formatted(resource, id));
    }
}
