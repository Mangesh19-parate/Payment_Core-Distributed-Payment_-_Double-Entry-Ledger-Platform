package com.platform.account.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertUser(UUID id, String email, String passwordHash, String status) {
        String sql = """
                INSERT INTO users (id, email, password_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """;
        jdbcTemplate.update(sql, id, email, passwordHash, status, Timestamp.from(Instant.now()));
    }
}
