package com.platform.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OutboxHealthMetricsService {

    public record OutboxHealthMetrics(
            long pendingOutboxEvents,
            long publishingOutboxEvents,
            long failedOutboxEvents,
            double oldestPendingEventAgeSeconds
    ) {}

    private final JdbcTemplate jdbcTemplate;

    public OutboxHealthMetricsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Calculates 4-way outbox health metrics (REQ-121).
     */
    public OutboxHealthMetrics getHealthMetrics() {
        String sql = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                COUNT(*) FILTER (WHERE status = 'PUBLISHING') AS publishing_count,
                COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_count,
                COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at) FILTER (WHERE status = 'PENDING'))), 0) AS oldest_pending_age
            FROM outbox_events
        """;

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new OutboxHealthMetrics(
                rs.getLong("pending_count"),
                rs.getLong("publishing_count"),
                rs.getLong("failed_count"),
                rs.getDouble("oldest_pending_age")
        ));
    }
}
