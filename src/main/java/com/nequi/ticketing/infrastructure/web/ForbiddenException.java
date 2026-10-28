package com.nequi.ticketing.infrastructure.web;

public class ForbiddenException extends RuntimeException {

    ForbiddenException() {
        super("This operation requires the admin role");
    }
}
