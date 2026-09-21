package com.platform.transfer.api;

import com.platform.transfer.domain.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        TransactionStatus status,
        long amount,
        String currency,
        String failureReason,
        Instant timestamp
) {}
