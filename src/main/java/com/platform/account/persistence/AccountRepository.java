package com.platform.account.persistence;

import com.platform.account.domain.Account;
import com.platform.account.domain.AccountStatus;
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
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Account> rowMapper = new RowMapper<>() {
        @Override
        public Account mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Account(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("owner_id"),
                    rs.getString("currency").trim(),
                    rs.getLong("cached_balance"),
                    rs.getLong("version"),
                    AccountStatus.valueOf(rs.getString("status")),
                    rs.getBoolean("is_system_account"),
                    rs.getTimestamp("created_at").toInstant()
            );
        }
    };

    /**
     * Acquires pessimistic row locks on accounts in strictly ascending UUID order (REQ-020).
     * Sorting IDs and locking sequentially guarantees no circular lock acquisition wait.
     */
    public List<Account> findAccountsForUpdate(UUID id1, UUID id2) {
        UUID firstId = id1.compareTo(id2) <= 0 ? id1 : id2;
        UUID secondId = id1.compareTo(id2) <= 0 ? id2 : id1;

        String sql = """
                SELECT id, owner_id, currency, cached_balance, version, status, is_system_account, created_at
                FROM accounts
                WHERE id = ?
                FOR UPDATE
                """;

        List<Account> firstList = jdbcTemplate.query(sql, rowMapper, firstId);
        if (firstList.isEmpty()) {
            return List.of();
        }

        List<Account> secondList = jdbcTemplate.query(sql, rowMapper, secondId);
        if (secondList.isEmpty()) {
            return firstList;
        }

        return List.of(firstList.get(0), secondList.get(0));
    }

    public Optional<Account> findById(UUID id) {
        String sql = """
                SELECT id, owner_id, currency, cached_balance, version, status, is_system_account, created_at
                FROM accounts
                WHERE id = ?
                """;
        List<Account> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void insertAccount(Account account) {
        String sql = """
                INSERT INTO accounts (id, owner_id, currency, cached_balance, version, status, is_system_account, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                account.id(),
                account.ownerId(),
                account.currency(),
                account.cachedBalance(),
                account.version(),
                account.status().name(),
                account.isSystemAccount(),
                Timestamp.from(account.createdAt() != null ? account.createdAt() : Instant.now())
        );
    }

    public int updateBalanceAndVersion(UUID accountId, long newBalance, long currentVersion) {
        String sql = """
                UPDATE accounts
                SET cached_balance = ?, version = version + 1
                WHERE id = ? AND version = ?
                """;
        return jdbcTemplate.update(sql, newBalance, accountId, currentVersion);
    }

    public int updateStatus(UUID accountId, AccountStatus status) {
        String sql = "UPDATE accounts SET status = ? WHERE id = ?";
        return jdbcTemplate.update(sql, status.name(), accountId);
    }

    public int updateBalance(UUID accountId, long newBalance) {
        String sql = "UPDATE accounts SET cached_balance = ?, version = version + 1 WHERE id = ?";
        return jdbcTemplate.update(sql, newBalance, accountId);
    }
}
