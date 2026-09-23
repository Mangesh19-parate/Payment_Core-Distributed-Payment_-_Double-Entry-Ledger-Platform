package com.platform.approval.persistence;

import com.platform.approval.domain.TransactionApproval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<TransactionApproval> rowMapper = (rs, rowNum) -> new TransactionApproval(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("transaction_id"),
            (UUID) rs.getObject("requested_by"),
            (UUID) rs.getObject("approved_by"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("approved_at") != null ? rs.getTimestamp("approved_at").toInstant() : null
    );

    public void createApproval(UUID transactionId, UUID requestedBy) {
        String sql = """
            INSERT INTO transaction_approvals (id, transaction_id, requested_by, status, created_at)
            VALUES (?, ?, ?, 'PENDING', ?)
        """;
        jdbcTemplate.update(sql, UUID.randomUUID(), transactionId, requestedBy, Timestamp.from(Instant.now()));
    }

    public Optional<TransactionApproval> findByTransactionId(UUID transactionId) {
        String sql = "SELECT * FROM transaction_approvals WHERE transaction_id = ?";
        List<TransactionApproval> results = jdbcTemplate.query(sql, rowMapper, transactionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void updateStatus(UUID transactionId, UUID actorId, String status, String reason) {
        String sql = """
            UPDATE transaction_approvals
            SET approved_by = ?, status = ?, reason = ?, approved_at = ?
            WHERE transaction_id = ?
        """;
        jdbcTemplate.update(sql, actorId, status, reason, Timestamp.from(Instant.now()), transactionId);
    }
}
