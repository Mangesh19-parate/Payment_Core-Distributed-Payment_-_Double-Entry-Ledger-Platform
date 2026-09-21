package com.platform.outbox.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        Long id,
        UUID aggregateId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        int attemptCount,
        String lastError,
        Instant leaseUntil,
        Instant createdAt,
        Instant publishedAt
) {
    public static OutboxEvent pending(UUID aggregateId, String eventType, String payloadJson) {
        return new OutboxEvent(
                null,
                aggregateId,
                eventType,
                payloadJson,
                OutboxEventStatus.PENDING,
                0,
                null,
                null,
                Instant.now(),
                null
        );
    }
}
