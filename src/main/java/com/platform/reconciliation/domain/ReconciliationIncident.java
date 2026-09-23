package com.platform.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationIncident(
        UUID id,
        UUID accountId,
        long cachedBalance,
        long ledgerBalance,
        long discrepancy,
        IncidentStatus status,
        Instant detectedAt,
        Instant resolvedAt,
        UUID resolvedBy,
        String resolutionNotes
) {
    public static ReconciliationIncident create(
            UUID accountId,
            long cachedBalance,
            long ledgerBalance,
            long discrepancy
    ) {
        return new ReconciliationIncident(
                UUID.randomUUID(),
                accountId,
                cachedBalance,
                ledgerBalance,
                discrepancy,
                IncidentStatus.DETECTED,
                Instant.now(),
                null,
                null,
                null
        );
    }
}
