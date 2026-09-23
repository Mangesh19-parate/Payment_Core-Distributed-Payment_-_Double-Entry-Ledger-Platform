package com.platform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AuditLogEntry entry) {
        String sql = """
            INSERT INTO audit_log (actor_id, action, resource_type, resource_id, request_id, result, reason, metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;

        jdbcTemplate.update(
                sql,
                entry.actorId(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.requestId(),
                entry.result(),
                entry.reason(),
                entry.metadata() != null ? entry.metadata() : "{}",
                Timestamp.from(entry.createdAt())
        );
    }
}
