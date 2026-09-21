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
}
