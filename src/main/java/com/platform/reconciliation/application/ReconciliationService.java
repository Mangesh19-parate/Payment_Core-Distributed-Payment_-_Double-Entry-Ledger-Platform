package com.platform.reconciliation.application;

import com.platform.account.domain.AccountStatus;
import com.platform.account.persistence.AccountRepository;
import com.platform.audit.AuditLogService;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.reconciliation.domain.IncidentStatus;
import com.platform.reconciliation.domain.ReconciliationIncident;
import com.platform.reconciliation.persistence.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AccountRepository accountRepository;
    private final IncidentRepository incidentRepository;
    private final AuditLogService auditLogService;

    public ReconciliationService(
            JdbcTemplate jdbcTemplate,
            AccountRepository accountRepository,
            IncidentRepository incidentRepository,
            AuditLogService auditLogService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
        this.incidentRepository = incidentRepository;
        this.auditLogService = auditLogService;
    }

    public record AccountBalanceComparison(
            UUID accountId,
            long cachedBalance,
            long ledgerBalance,
            long discrepancy
    ) {}

    /**
     * Executes reconciliation check across all accounts (REQ-080).
     * Compares accounts.cached_balance against SUM(ledger_entries) per account.
     * Enforces zero silent auto-correction policy (REQ-081).
     * Drifted accounts transition to SUSPENDED status (REQ-083).
     */
    @Transactional
    public List<ReconciliationIncident> reconcileAllAccounts() {
        String sql = """
            SELECT
                a.id AS account_id,
                a.cached_balance AS cached_balance,
                COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE -l.amount END), 0) AS ledger_balance
            FROM accounts a
            LEFT JOIN ledger_entries l ON a.id = l.account_id
            GROUP BY a.id, a.cached_balance
        """;

        List<AccountBalanceComparison> comparisons = jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID accountId = (UUID) rs.getObject("account_id");
            long cached = rs.getLong("cached_balance");
            long ledger = rs.getLong("ledger_balance");
            return new AccountBalanceComparison(accountId, cached, ledger, cached - ledger);
        });

        List<ReconciliationIncident> newIncidents = new java.util.ArrayList<>();

        for (AccountBalanceComparison comp : comparisons) {
            if (comp.discrepancy() != 0) {
                // REQ-081: Alert and do NOT auto-correct
                log.error("CRITICAL RECONCILIATION DRIFT DETECTED: account={}, cachedBalance={}, ledgerBalance={}, discrepancy={}",
                        comp.accountId(), comp.cachedBalance(), comp.ledgerBalance(), comp.discrepancy());

                // REQ-083: Transition account to SUSPENDED to block further operations
                accountRepository.updateStatus(comp.accountId(), AccountStatus.SUSPENDED);
                log.warn("Account {} suspended due to ledger balance drift", comp.accountId());

                // Check if an open incident already exists
                Optional<ReconciliationIncident> existingIncident = incidentRepository.findOpenIncidentByAccountId(comp.accountId());
                if (existingIncident.isEmpty()) {
                    ReconciliationIncident incident = ReconciliationIncident.create(
                            comp.accountId(),
                            comp.cachedBalance(),
                            comp.ledgerBalance(),
                            comp.discrepancy()
                    );
                    incidentRepository.insert(incident);
                    newIncidents.add(incident);
                    log.info("Created reconciliation incident {} for account {}", incident.id(), comp.accountId());
                }
            }
        }

        return newIncidents;
    }

    /**
     * Executes the explicit 6-step remediation workflow (REQ-082):
     * Detect -> Flag -> Alert -> Investigate -> Correct -> Record (Audit Log).
     */
    @Transactional
    public void remediateIncident(
            UUID incidentId,
            UUID adminUserId,
            long correctedBalance,
            String resolutionNotes
    ) {
        ReconciliationIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "Incident not found: " + incidentId));

        if (incident.status() == IncidentStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Incident is already resolved");
        }

        UUID accountId = incident.accountId();
        Instant now = Instant.now();

        // 5. Correct: explicit update of balance and restore account to ACTIVE
        accountRepository.updateBalance(accountId, correctedBalance);
        accountRepository.updateStatus(accountId, AccountStatus.ACTIVE);

        // 6. Record: write explicit audit log
        String metadata = String.format(
                "{\"incidentId\":\"%s\",\"previousCachedBalance\":%d,\"previousLedgerBalance\":%d,\"discrepancy\":%d,\"correctedBalance\":%d}",
                incidentId, incident.cachedBalance(), incident.ledgerBalance(), incident.discrepancy(), correctedBalance
        );

        auditLogService.logAction(
                adminUserId,
                "RECONCILIATION_REMEDIATION",
                "ACCOUNT",
                accountId,
                incidentId.toString(),
                "SUCCESS",
                resolutionNotes,
                metadata
        );

        // Update incident status to RESOLVED
        incidentRepository.resolveIncident(incidentId, IncidentStatus.RESOLVED, adminUserId, resolutionNotes, now);

        log.info("Reconciliation incident {} resolved for account {} by admin {}. New balance: {}",
                incidentId, accountId, adminUserId, correctedBalance);
    }
}
