package com.platform.transfer;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class LedgerAppendOnlySecurityTest extends BaseIntegrationTest {

    @Autowired
    private TransferApplicationService transferApplicationService;

    @Autowired
    private AccountApplicationService accountApplicationService;

    @Test
    @DisplayName("REQ-001: Database-level trigger strictly forbids UPDATE operations on ledger_entries")
    void updateLedgerEntries_failsWithTriggerException() {
        UUID userA = createTestUser();
        UUID userB = createTestUser();
        Account accA = accountApplicationService.createAccount(userA, "INR");
        Account accB = accountApplicationService.createAccount(userB, "INR");
        accountApplicationService.fundAccount(userA, "fund-" + UUID.randomUUID(), accA.id(), 100_000L);

        TransferResult result = transferApplicationService.transfer(
                userA, "tx-" + UUID.randomUUID(), accA.id(), accB.id(), 5000L, "INR"
        );
        assertTrue(result instanceof TransferResult.Posted);
        UUID txnId = ((TransferResult.Posted) result).transactionId();

        // Attempt direct UPDATE on ledger_entries
        assertThrows(DataAccessException.class, () -> {
            testJdbcTemplate.update(
                    "UPDATE ledger_entries SET amount = amount + 1000 WHERE transaction_id = ?",
                    txnId
            );
        }, "Database must reject UPDATE on immutable ledger_entries");
    }

    @Test
    @DisplayName("REQ-001: Database-level trigger strictly forbids DELETE operations on ledger_entries")
    void deleteLedgerEntries_failsWithTriggerException() {
        UUID userA = createTestUser();
        UUID userB = createTestUser();
        Account accA = accountApplicationService.createAccount(userA, "INR");
        Account accB = accountApplicationService.createAccount(userB, "INR");
        accountApplicationService.fundAccount(userA, "fund-" + UUID.randomUUID(), accA.id(), 100_000L);

        TransferResult result = transferApplicationService.transfer(
                userA, "tx-" + UUID.randomUUID(), accA.id(), accB.id(), 5000L, "INR"
        );
        assertTrue(result instanceof TransferResult.Posted);
        UUID txnId = ((TransferResult.Posted) result).transactionId();

        // Attempt direct DELETE on ledger_entries
        assertThrows(DataAccessException.class, () -> {
            testJdbcTemplate.update(
                    "DELETE FROM ledger_entries WHERE transaction_id = ?",
                    txnId
            );
        }, "Database must reject DELETE on immutable ledger_entries");
    }

    @Test
    @DisplayName("REQ-103: Database-level trigger strictly forbids UPDATE/DELETE on audit_log")
    void mutateAuditLog_failsWithTriggerException() {
        UUID user = createTestUser();

        // Attempt direct UPDATE on audit_log
        assertThrows(DataAccessException.class, () -> {
            testJdbcTemplate.update("UPDATE audit_log SET result = 'TAMPERED'");
        }, "Database must reject UPDATE on immutable audit_log");

        // Attempt direct DELETE on audit_log
        assertThrows(DataAccessException.class, () -> {
            testJdbcTemplate.update("DELETE FROM audit_log");
        }, "Database must reject DELETE on immutable audit_log");
    }
}
