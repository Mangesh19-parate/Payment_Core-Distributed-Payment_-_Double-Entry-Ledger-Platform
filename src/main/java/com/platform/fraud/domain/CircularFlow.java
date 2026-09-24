package com.platform.fraud.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CircularFlow(
        String ringId,
        int hopCount,
        List<UUID> accountSequence,
        long totalCirculatedAmount,
        Instant firstTransferAt,
        Instant lastTransferAt
) {}
