package com.platform.outbox.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a transaction reaches POSTED state.
 */
public record TransactionPostedEvent(
        UUID transactionId,
        UUID principalId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountPaise,
        String currency,
        String transactionType,
        Instant postedAt
) {}
