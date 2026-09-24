package com.platform.reconciliation;

import com.platform.BaseIntegrationTest;
import com.platform.account.domain.Account;
import com.platform.account.domain.AccountStatus;
import com.platform.account.persistence.AccountRepository;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.reconciliation.application.ReconciliationService;
import com.platform.reconciliation.domain.IncidentStatus;
import com.platform.reconciliation.domain.ReconciliationIncident;
import com.platform.reconciliation.persistence.IncidentRepository;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ReconciliationDriftTest extends BaseIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferApplicationService transferApplicationService;

    private static final UUID SYSTEM_CASH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Test
    @DisplayName("REQ-080, REQ-081, REQ-082, REQ-083: Reconciliation detects balance drift, suspends account, rejects silent fix, and enforces 6-step remediation")
    void testReconciliationDetectionAndRemediationWorkflow() {
        UUID userId = createTestUser();
        UUID adminId = createTestUser();

        // 1. Create and fund user account
        UUID accountId = UUID.randomUUID();
        Account account = new Account(
                accountId, userId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()
        );
        accountRepository.insertAccount(account);

        // Fund ₹1,000 (100,000 paise) from SYSTEM_CASH
        TransferResult fundResult = transferApplicationService.transfer(
                userId, "fund-recon-" + UUID.randomUUID(), SYSTEM_CASH_ID, accountId, 100_000L, "INR"
        );
        assertTrue(fundResult instanceof TransferResult.Posted);

        // 2. Initial reconciliation passes cleanly
        List<ReconciliationIncident> cleanIncidents = reconciliationService.reconcileAllAccounts();
        assertTrue(cleanIncidents.isEmpty(), "Initial state must have zero discrepancy");

        // 3. Manually corrupt cached_balance directly in DB (simulating silent data drift or bug)
        long corruptedBalance = 150_000L; // +50,000 paise drift
        testJdbcTemplate.update("UPDATE accounts SET cached_balance = ? WHERE id = ?", corruptedBalance, accountId);

        // 4. Run reconciliation job (REQ-080)
        List<ReconciliationIncident> detectedIncidents = reconciliationService.reconcileAllAccounts();
        assertEquals(1, detectedIncidents.size());

        ReconciliationIncident incident = detectedIncidents.get(0);
        assertEquals(accountId, incident.accountId());
        assertEquals(corruptedBalance, incident.cachedBalance());
        assertEquals(100_000L, incident.ledgerBalance());
        assertEquals(50_000L, incident.discrepancy());
        assertEquals(IncidentStatus.DETECTED, incident.status());

        // 5. REQ-081: Assert NO silent auto-correction took place
        Account corruptedAccount = accountRepository.findById(accountId).orElseThrow();
        assertEquals(corruptedBalance, corruptedAccount.cachedBalance(), "Cached balance must NOT be silently auto-corrected");

        // 6. REQ-083: Account is transitioned to SUSPENDED
        assertEquals(AccountStatus.SUSPENDED, corruptedAccount.status(), "Drifted account must be SUSPENDED");

        // 7. Subsequent transfers on suspended account fail with ACCOUNT_SUSPENDED
        UUID destId = UUID.randomUUID();
        accountRepository.insertAccount(new Account(destId, userId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));

        TransferResult suspendedResult = transferApplicationService.transfer(
                userId, "tx-blocked-" + UUID.randomUUID(), accountId, destId, 10_000L, "INR"
        );
        assertTrue(suspendedResult instanceof TransferResult.BusinessFailure);
        assertEquals(ErrorCode.ACCOUNT_SUSPENDED, ((TransferResult.BusinessFailure) suspendedResult).errorCode());

        // 8. REQ-082: Explicit 6-step audited remediation workflow
        long trueLedgerBalance = 100_000L;
        reconciliationService.remediateIncident(
                incident.id(),
                adminId,
                trueLedgerBalance,
                "Investigated manual DB tampering - restored authoritative balance from ledger"
        );

        // 9. Verify remediation effects
        Account remediatedAccount = accountRepository.findById(accountId).orElseThrow();
        assertEquals(trueLedgerBalance, remediatedAccount.cachedBalance());
        assertEquals(AccountStatus.ACTIVE, remediatedAccount.status());

        ReconciliationIncident resolvedIncident = incidentRepository.findById(incident.id()).orElseThrow();
        assertEquals(IncidentStatus.RESOLVED, resolvedIncident.status());
        assertEquals(adminId, resolvedIncident.resolvedBy());
        assertNotNull(resolvedIncident.resolvedAt());

        // 10. Verify audit log entry exists (REQ-103)
        Integer auditCount = testJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'RECONCILIATION_REMEDIATION' AND resource_id = ?",
                Integer.class,
                accountId
        );
        assertNotNull(auditCount);
        assertEquals(1, auditCount);

        // 11. Subsequent reconciliation finds no new drift
        List<ReconciliationIncident> postRemediationIncidents = reconciliationService.reconcileAllAccounts();
        assertTrue(postRemediationIncidents.isEmpty());
    }
}
