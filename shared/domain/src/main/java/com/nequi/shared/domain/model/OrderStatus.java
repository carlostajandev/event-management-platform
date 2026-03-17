package com.nequi.shared.domain.model;

public enum OrderStatus {
    PENDING_CONFIRMATION,  // written to DB + outbox atomically; awaiting SQS processing
    CONFIRMED,             // SQS consumer processed successfully — reservation confirmed
    COMPLETED,             // payment captured, tickets issued
    FAILED                 // processing failed — triggers reservation cancellation
}