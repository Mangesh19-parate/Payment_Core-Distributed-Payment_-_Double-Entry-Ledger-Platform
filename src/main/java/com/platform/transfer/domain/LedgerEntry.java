package com.platform.transfer.domain;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        Long id,
        UUID transactionId,
        UUID accountId,
        EntryType entryType,
        long amount,
        String currency,
        Instant createdAt
) {}
