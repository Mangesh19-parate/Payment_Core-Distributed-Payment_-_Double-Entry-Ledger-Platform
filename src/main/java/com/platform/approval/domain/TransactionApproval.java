package com.platform.approval.domain;

import java.time.Instant;
import java.util.UUID;

public record TransactionApproval(
        UUID id,
        UUID transactionId,
        UUID requestedBy,
        UUID approvedBy,
        String status,
        String reason,
        Instant createdAt,
        Instant approvedAt
) {}
