package com.platform.outbox.persistence;

import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.domain.OutboxEventStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<OutboxEvent> rowMapper = (rs, rowNum) -> new OutboxEvent(
            rs.getLong("id"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            OutboxEventStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            rs.getString("last_error"),
            rs.getTimestamp("lease_until") != null ? rs.getTimestamp("lease_until").toInstant() : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant() : null
    );

    /**
     * Inserts outbox events in batch as part of the caller's active database transaction (REQ-040).
     */
    public void saveAll(List<OutboxEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO outbox_events (aggregate_id, event_type, payload, status, attempt_count, created_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?)
        """;

        jdbcTemplate.batchUpdate(sql, events, events.size(), (ps, event) -> {
            ps.setObject(1, event.aggregateId());
            ps.setString(2, event.eventType());
            ps.setString(3, event.payload());
            ps.setString(4, event.status().name());
            ps.setInt(5, event.attemptCount());
            ps.setTimestamp(6, Timestamp.from(event.createdAt()));
        });
    }

    /**
     * Atomically claims a batch of pending/expired events using SELECT ... FOR UPDATE SKIP LOCKED (REQ-041, REQ-042, REQ-043).
     * Runs in its own short transaction so locks are released immediately after claim.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPendingEvents(int batchSize, Duration leaseDuration) {
        long leaseSeconds = Math.max(1, leaseDuration.toSeconds());

        String sql = """
            WITH to_claim AS (
                SELECT id FROM outbox_events
                WHERE (status = 'PENDING' OR (status = 'PUBLISHING' AND lease_until <= now()))
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_events o
            SET status = 'PUBLISHING',
                attempt_count = attempt_count + 1,
                lease_until = now() + (? * INTERVAL '1 second')
            FROM to_claim
            WHERE o.id = to_claim.id
            RETURNING o.id, o.aggregate_id, o.event_type, o.payload, o.status, o.attempt_count, o.last_error, o.lease_until, o.created_at, o.published_at
        """;

        return jdbcTemplate.query(sql, rowMapper, batchSize, leaseSeconds);
    }

    /**
     * Marks successfully published events in a separate short transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }

        String inClause = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        String sql = "UPDATE outbox_events SET status = 'PUBLISHED', published_at = now(), lease_until = NULL WHERE id IN (" + inClause + ")";

        jdbcTemplate.update(sql, eventIds.toArray());
    }

    /**
     * Records publishing failure with retry backoff or moves to FAILED if max attempts exceeded (REQ-043, REQ-048).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long eventId, String errorMessage, int currentAttempts, int maxAttempts, Duration backoff) {
        if (currentAttempts >= maxAttempts) {
            String sql = """
                UPDATE outbox_events
                SET status = 'FAILED',
                    last_error = ?,
                    lease_until = NULL
                WHERE id = ?
            """;
            jdbcTemplate.update(sql, errorMessage, eventId);
        } else {
            long backoffSeconds = Math.max(1, backoff.toSeconds());
            String sql = """
                UPDATE outbox_events
                SET status = 'PENDING',
                    last_error = ?,
                    lease_until = now() + (? * INTERVAL '1 second')
                WHERE id = ?
            """;
            jdbcTemplate.update(sql, errorMessage, backoffSeconds, eventId);
        }
    }
}
