package com.platform.transfer.domain;

import com.platform.common.Money;
import com.platform.common.error.ErrorCode;

import java.time.Instant;
import java.util.UUID;

public sealed interface TransferResult {

    record Posted(
            UUID transactionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            Money amount,
            Instant postedAt
    ) implements TransferResult {}

    record BusinessFailure(
            ErrorCode errorCode,
            String reason
    ) implements TransferResult {}

    record IdempotentReplay(
            UUID transactionId,
            TransactionStatus status,
            Money amount,
            String failureReason
    ) implements TransferResult {}

    record AwaitingApproval(
            UUID transactionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            Money amount,
            Instant createdAt
    ) implements TransferResult {}
}
