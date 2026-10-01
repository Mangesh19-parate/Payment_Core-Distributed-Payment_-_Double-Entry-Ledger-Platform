package com.platform.transfer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * REQ-027: Scheduled cleanup job for monitoring and managing idempotency key lifecycle.
 * Preserves financial transaction & ledger history while tracking expired idempotency key windows.
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.idempotency.cleanup-cron:0 0 * * * *}")
    public int cleanupExpiredIdempotencyKeys() {
        String countSql = "SELECT COUNT(*) FROM transactions WHERE expires_at <= now()";
        Integer expiredCount = jdbcTemplate.queryForObject(countSql, Integer.class);
        int count = expiredCount != null ? expiredCount : 0;
        if (count > 0) {
            log.info("REQ-027: {} transactions passed the 24h idempotency expiration window", count);
        }
        return count;
    }
}
