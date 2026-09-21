package com.platform.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Source account has insufficient balance"),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Currency does not match account or platform standard"),
    ACCOUNT_SUSPENDED(HttpStatus.UNPROCESSABLE_ENTITY, "Account is currently suspended"),
    ACCOUNT_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "Account is closed"),
    SAME_ACCOUNT_TRANSFER(HttpStatus.UNPROCESSABLE_ENTITY, "Source and destination accounts must be distinct"),
    AMOUNT_OUT_OF_BOUNDS(HttpStatus.BAD_REQUEST, "Amount must be greater than zero and within permitted limits"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key reused with different request parameters"),
    ACCOUNT_LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "Could not acquire account lock within timeout"),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account does not exist"),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction does not exist"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request payload or headers"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
