package com.nequi.ticketing.infrastructure.web;

public class CallerNotIdentifiedException extends RuntimeException {

    CallerNotIdentifiedException() {
        super("Missing caller identity (X-User-Id header)");
    }
}
