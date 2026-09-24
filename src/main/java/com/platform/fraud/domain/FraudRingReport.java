package com.platform.fraud.domain;

import java.time.Instant;
import java.util.List;

public record FraudRingReport(
        int totalTransactionsAnalyzed,
        int totalAccountsScanned,
        List<CircularFlow> detectedCircularRings,
        List<MuleAccount> suspectedMules,
        Instant generatedAt
) {}
