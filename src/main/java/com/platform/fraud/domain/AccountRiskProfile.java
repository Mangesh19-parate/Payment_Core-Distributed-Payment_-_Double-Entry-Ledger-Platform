package com.platform.fraud.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountRiskProfile(
        UUID accountId,
        String riskLevel, // LOW, MEDIUM, HIGH, CRITICAL
        int riskScore,    // 0 to 100
        boolean inCircularRing,
        boolean suspectedMule,
        long totalInboundAmount,
        long totalOutboundAmount,
        double retentionRatio,
        List<String> riskFactors,
        Instant evaluatedAt
) {}
