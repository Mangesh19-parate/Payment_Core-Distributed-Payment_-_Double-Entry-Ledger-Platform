package com.platform.fraud.domain;

import java.time.Instant;
import java.util.UUID;

public record MuleAccount(
        UUID accountId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long incomingAmount,
        long outgoingAmount,
        long dwellTimeSeconds,
        double passThroughRatio,
        Instant detectedAt
) {}
