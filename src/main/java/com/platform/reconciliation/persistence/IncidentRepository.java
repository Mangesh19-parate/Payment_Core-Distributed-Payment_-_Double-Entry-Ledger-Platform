package com.platform.reconciliation.persistence;

import com.platform.reconciliation.domain.IncidentStatus;
import com.platform.reconciliation.domain.ReconciliationIncident;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IncidentRepository {

    private final JdbcTemplate jdbcTemplate;

    public IncidentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<ReconciliationIncident> rowMapper = (rs, rowNum) -> new ReconciliationIncident(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("account_id"),
            rs.getLong("cached_balance"),
            rs.getLong("ledger_balance"),
            rs.getLong("discrepancy"),
            IncidentStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("detected_at").toInstant(),
            rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
            (UUID) rs.getObject("resolved_by"),
            rs.getString("resolution_notes")
    );

    public void insert(ReconciliationIncident incident) {
        String sql = """
            INSERT INTO reconciliation_incidents
            (id, account_id, cached_balance, ledger_balance, discrepancy, status, detected_at, resolved_at, resolved_by, resolution_notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        jdbcTemplate.update(sql,
                incident.id(),
                incident.accountId(),
                incident.cachedBalance(),
                incident.ledgerBalance(),
                incident.discrepancy(),
                incident.status().name(),
                Timestamp.from(incident.detectedAt()),
                incident.resolvedAt() != null ? Timestamp.from(incident.resolvedAt()) : null,
                incident.resolvedBy(),
                incident.resolutionNotes()
        );
    }

    public Optional<ReconciliationIncident> findById(UUID id) {
        String sql = "SELECT * FROM reconciliation_incidents WHERE id = ?";
        List<ReconciliationIncident> list = jdbcTemplate.query(sql, rowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<ReconciliationIncident> findOpenIncidentByAccountId(UUID accountId) {
        String sql = """
            SELECT * FROM reconciliation_incidents
            WHERE account_id = ? AND status IN ('DETECTED', 'INVESTIGATING')
            ORDER BY detected_at DESC
            LIMIT 1
        """;
        List<ReconciliationIncident> list = jdbcTemplate.query(sql, rowMapper, accountId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<ReconciliationIncident> findOpenIncidents() {
        String sql = "SELECT * FROM reconciliation_incidents WHERE status IN ('DETECTED', 'INVESTIGATING') ORDER BY detected_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    public void resolveIncident(UUID incidentId, IncidentStatus status, UUID resolvedBy, String resolutionNotes, Instant resolvedAt) {
        String sql = """
            UPDATE reconciliation_incidents
            SET status = ?, resolved_by = ?, resolution_notes = ?, resolved_at = ?
            WHERE id = ?
        """;
        jdbcTemplate.update(sql,
                status.name(),
                resolvedBy,
                resolutionNotes,
                Timestamp.from(resolvedAt != null ? resolvedAt : Instant.now()),
                incidentId
        );
    }
}
