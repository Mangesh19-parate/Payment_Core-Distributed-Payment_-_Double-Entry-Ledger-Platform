package com.platform.transfer.domain;

import java.time.Instant;
import java.util.UUID;

public record Transaction(
        UUID id,
        UUID principalId,
        String idempotencyKey,
        String requestHash,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amount,
        String currency,
        TransactionStatus status,
        String failureReason,
        TransactionType type,
        UUID referenceTxnId,
        Instant createdAt,
        Instant postedAt
) {}
