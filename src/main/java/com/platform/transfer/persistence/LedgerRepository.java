package com.platform.transfer.persistence;

import com.platform.transfer.domain.EntryType;
import com.platform.transfer.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<LedgerEntry> rowMapper = new RowMapper<>() {
        @Override
        public LedgerEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LedgerEntry(
                    rs.getLong("id"),
                    (UUID) rs.getObject("transaction_id"),
                    (UUID) rs.getObject("account_id"),
                    EntryType.valueOf(rs.getString("entry_type")),
                    rs.getLong("amount"),
                    rs.getString("currency").trim(),
                    rs.getTimestamp("created_at").toInstant()
            );
        }
    };

    public void insertEntries(List<LedgerEntry> entries) {
        String sql = """
                INSERT INTO ledger_entries (transaction_id, account_id, entry_type, amount, currency, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.batchUpdate(sql, entries, entries.size(), (ps, entry) -> {
            ps.setObject(1, entry.transactionId());
            ps.setObject(2, entry.accountId());
            ps.setString(3, entry.entryType().name());
            ps.setLong(4, entry.amount());
            ps.setString(5, entry.currency());
            ps.setTimestamp(6, Timestamp.from(entry.createdAt() != null ? entry.createdAt() : Instant.now()));
        });
    }

    public List<LedgerEntry> findByAccountId(UUID accountId, int limit, int offset) {
        String sql = """
                SELECT id, transaction_id, account_id, entry_type, amount, currency, created_at
                FROM ledger_entries
                WHERE account_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(sql, rowMapper, accountId, limit, offset);
    }

    public List<LedgerEntry> findByTransactionId(UUID transactionId) {
        String sql = """
                SELECT id, transaction_id, account_id, entry_type, amount, currency, created_at
                FROM ledger_entries
                WHERE transaction_id = ?
                ORDER BY id ASC
                """;
        return jdbcTemplate.query(sql, rowMapper, transactionId);
    }

    public long computeBalanceFromLedger(UUID accountId) {
        String sql = """
                SELECT COALESCE(SUM(
                    CASE 
                        WHEN entry_type = 'CREDIT' THEN amount 
                        WHEN entry_type = 'DEBIT' THEN -amount 
                        ELSE 0 
                    END
                ), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """;
        Long balance = jdbcTemplate.queryForObject(sql, Long.class, accountId);
        return balance != null ? balance : 0L;
    }
}
