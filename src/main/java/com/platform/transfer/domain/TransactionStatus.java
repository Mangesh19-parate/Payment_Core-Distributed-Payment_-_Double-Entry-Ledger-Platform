package com.platform.transfer.domain;

public enum TransactionStatus {
    CREATED,
    AWAITING_APPROVAL,
    POSTED,
    FAILED,
    REJECTED,
    REVERSED
}
