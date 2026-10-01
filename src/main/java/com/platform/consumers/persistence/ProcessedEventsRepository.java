package com.platform.consumers.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Inbox Pattern Repository (REQ-046).
 * Atomically records event consumption to ensure idempotent message processing.
 */
@Repository
public class ProcessedEventsRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempts to record event processing for a specific consumer.
     * Returns true if newly recorded; false if already processed.
     */
    public boolean tryRecordEvent(String consumerName, UUID eventId) {
        String sql = """
            INSERT INTO processed_events (consumer_name, event_id, processed_at)
            VALUES (?, ?, now())
            ON CONFLICT (consumer_name, event_id) DO NOTHING
        """;

        int rows = jdbcTemplate.update(sql, consumerName, eventId);
        return rows > 0;
    }

    public boolean isEventProcessed(String consumerName, UUID eventId) {
        String sql = "SELECT COUNT(*) FROM processed_events WHERE consumer_name = ? AND event_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, consumerName, eventId);
        return count != null && count > 0;
    }

    /**
     * Atomically validates and updates the last applied event version for an account (REQ-044).
     * Returns true if this version is strictly greater than the previously applied version.
     */
    public boolean tryUpdateAccountVersion(String consumerName, UUID accountId, long newVersion) {
        String sql = """
            INSERT INTO consumer_event_versions (consumer_name, account_id, last_applied_version, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (consumer_name, account_id)
            DO UPDATE SET last_applied_version = EXCLUDED.last_applied_version, updated_at = now()
            WHERE consumer_event_versions.last_applied_version < EXCLUDED.last_applied_version
        """;
        int rows = jdbcTemplate.update(sql, consumerName, accountId, newVersion);
        return rows > 0;
    }
}
