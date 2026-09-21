package com.platform.outbox.domain;

/**
 * Lifecycle states for outbox events (REQ-043).
 */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED
}
