package com.platform.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntry(
        Long id,
        UUID actorId,
        String action,
        String resourceType,
        UUID resourceId,
        String requestId,
        String result,
        String reason,
        String metadata,
        Instant createdAt
) {
    public static AuditLogEntry of(
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String requestId,
            String result,
            String reason,
            String metadata
    ) {
        return new AuditLogEntry(
                null,
                actorId,
                action,
                resourceType,
                resourceId,
                requestId,
                result,
                reason,
                metadata,
                Instant.now()
        );
    }
}
