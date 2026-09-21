package com.platform.outbox.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published per account touched in a transaction (REQ-044).
 * Carries accountVersion to detect and reject/buffer out-of-order delivery.
 */
public record AccountBalanceChangedEvent(
        UUID accountId,
        UUID transactionId,
        long deltaPaise,
        long newBalancePaise,
        long accountVersion,
        String currency,
        Instant occurredAt
) {}
