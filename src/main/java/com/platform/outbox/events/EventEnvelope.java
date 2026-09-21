package com.platform.outbox.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned event envelope (REQ-045).
 * Standard wrapper for all events published to Kafka.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID aggregateId,
        T payload
) {
    public static <T> EventEnvelope<T> of(String eventType, int eventVersion, UUID aggregateId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                eventVersion,
                Instant.now(),
                aggregateId,
                payload
        );
    }
}
