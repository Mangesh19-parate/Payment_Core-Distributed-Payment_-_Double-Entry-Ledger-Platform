package com.platform.transfer.persistence;

import com.platform.transfer.domain.Transaction;
import com.platform.transfer.domain.TransactionStatus;
import com.platform.transfer.domain.TransactionType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Transaction> rowMapper = new RowMapper<>() {
        @Override
        public Transaction mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp postedAtTs = rs.getTimestamp("posted_at");
            UUID refTxnId = (UUID) rs.getObject("reference_txn_id");
            return new Transaction(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("principal_id"),
                    rs.getString("idempotency_key"),
                    rs.getString("request_hash"),
                    (UUID) rs.getObject("source_account_id"),
                    (UUID) rs.getObject("destination_account_id"),
                    rs.getLong("amount"),
                    rs.getString("currency").trim(),
                    TransactionStatus.valueOf(rs.getString("status")),
                    rs.getString("failure_reason"),
                    TransactionType.valueOf(rs.getString("type")),
                    refTxnId,
                    rs.getTimestamp("created_at").toInstant(),
                    postedAtTs != null ? postedAtTs.toInstant() : null
            );
        }
    };

    /**
     * Attempts atomic initial insert with ON CONFLICT DO NOTHING (REQ-023).
     * Returns Optional with transaction ID if row was inserted, or empty if key conflict occurred.
     */
    public Optional<UUID> tryInsertInitialTransaction(
            UUID id,
            UUID principalId,
            String idempotencyKey,
            String requestHash,
            UUID sourceAccountId,
            UUID destinationAccountId,
            long amount,
            String currency,
            TransactionType type
    ) {
        String sql = """
                INSERT INTO transactions (
                    id, principal_id, idempotency_key, request_hash,
                    source_account_id, destination_account_id, amount, currency,
                    status, type, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                ON CONFLICT (principal_id, idempotency_key) DO NOTHING
                RETURNING id
                """;
        try {
            UUID insertedId = jdbcTemplate.queryForObject(
                    sql,
                    UUID.class,
                    id,
                    principalId,
                    idempotencyKey,
                    requestHash,
                    sourceAccountId,
                    destinationAccountId,
                    amount,
                    currency,
                    type.name(),
                    Timestamp.from(Instant.now())
            );
            return Optional.ofNullable(insertedId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Transaction> findByPrincipalAndIdempotencyKey(UUID principalId, String idempotencyKey) {
        String sql = """
                SELECT id, principal_id, idempotency_key, request_hash,
                       source_account_id, destination_account_id, amount, currency,
                       status, failure_reason, type, reference_txn_id, created_at, posted_at
                FROM transactions
                WHERE principal_id = ? AND idempotency_key = ?
                """;
        List<Transaction> results = jdbcTemplate.query(sql, rowMapper, principalId, idempotencyKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<Transaction> findById(UUID id) {
        String sql = """
                SELECT id, principal_id, idempotency_key, request_hash,
                       source_account_id, destination_account_id, amount, currency,
                       status, failure_reason, type, reference_txn_id, created_at, posted_at
                FROM transactions
                WHERE id = ?
                """;
        List<Transaction> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int updateStatus(UUID transactionId, TransactionStatus status, String failureReason, Instant postedAt) {
        String sql = """
                UPDATE transactions
                SET status = ?, failure_reason = ?, posted_at = ?
                WHERE id = ?
                """;
        return jdbcTemplate.update(
                sql,
                status.name(),
                failureReason,
                postedAt != null ? Timestamp.from(postedAt) : null,
                transactionId
        );
    }
}
