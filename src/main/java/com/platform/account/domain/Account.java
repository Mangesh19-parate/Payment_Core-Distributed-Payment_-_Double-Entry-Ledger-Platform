package com.platform.account.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,
        UUID ownerId,
        String currency,
        long cachedBalance,
        long version,
        AccountStatus status,
        boolean isSystemAccount,
        Instant createdAt
) {
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
