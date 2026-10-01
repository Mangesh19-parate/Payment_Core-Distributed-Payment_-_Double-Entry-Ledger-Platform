package com.platform.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthenticatedUser> findById(UUID id) {
        String sql = "SELECT id, email, password_hash, role, status FROM users WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRow, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AuthenticatedUser> findByEmail(String email) {
        String sql = "SELECT id, email, password_hash, role, status FROM users WHERE LOWER(email) = LOWER(?)";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRow, email));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public AuthenticatedUser createUser(UUID id, String email, String passwordHash, UserRole role) {
        String sql = "INSERT INTO users (id, email, password_hash, role, status) VALUES (?, ?, ?, ?, 'ACTIVE')";
        jdbcTemplate.update(sql, id, email.toLowerCase().trim(), passwordHash, role.name());
        return new AuthenticatedUser(id, email.toLowerCase().trim(), passwordHash, role, true);
    }

    private AuthenticatedUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuthenticatedUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                UserRole.fromString(rs.getString("role")),
                "ACTIVE".equalsIgnoreCase(rs.getString("status"))
        );
    }
}
