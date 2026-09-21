package com.platform.transfer;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.LedgerEntry;
import com.platform.transfer.domain.Transaction;
import com.platform.transfer.domain.TransactionStatus;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FundingAndTransferFlowTest extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("REQ-010, REQ-004: Fund account via SYSTEM_CASH and execute transfer with balanced ledger")
    void testFundingAndTransferFlow() {
        UUID ownerA = createTestUser();
        UUID ownerB = createTestUser();

        Account accountA = accountService.createAccount(ownerA, "INR");
        Account accountB = accountService.createAccount(ownerB, "INR");

        // 1. Fund Account A with 10,000 paise (₹100) via SYSTEM_CASH
        String fundKey = UUID.randomUUID().toString();
        TransferResult fundResult = accountService.fundAccount(ownerA, fundKey, accountA.id(), 10_000L);
        assertThat(fundResult).isInstanceOf(TransferResult.Posted.class);

        Account updatedA = accountService.getAccount(accountA.id());
        assertThat(updatedA.cachedBalance()).isEqualTo(10_000L);

        // Verify ledger entries for funding
        List<LedgerEntry> ledgerA = accountService.getAccountLedger(accountA.id(), 10, 0);
        assertThat(ledgerA).hasSize(1);
        assertThat(ledgerA.get(0).amount()).isEqualTo(10_000L);

        // 2. Transfer 4,000 paise from A to B
        String transferKey = UUID.randomUUID().toString();
        TransferResult transferResult = transferService.transfer(
                ownerA, transferKey, accountA.id(), accountB.id(), 4_000L, "INR"
        );
        assertThat(transferResult).isInstanceOf(TransferResult.Posted.class);
        TransferResult.Posted posted = (TransferResult.Posted) transferResult;

        // Check balances
        Account finalA = accountService.getAccount(accountA.id());
        Account finalB = accountService.getAccount(accountB.id());
        assertThat(finalA.cachedBalance()).isEqualTo(6_000L);
        assertThat(finalB.cachedBalance()).isEqualTo(4_000L);

        // Check balance reconciliation summaries
        AccountApplicationService.AccountBalanceSummary summaryA = accountService.getBalanceSummary(accountA.id());
        AccountApplicationService.AccountBalanceSummary summaryB = accountService.getBalanceSummary(accountB.id());
        assertThat(summaryA.delta()).isEqualTo(0L);
        assertThat(summaryB.delta()).isEqualTo(0L);
        assertThat(summaryA.ledgerDerivedBalance()).isEqualTo(6_000L);
        assertThat(summaryB.ledgerDerivedBalance()).isEqualTo(4_000L);

        // Check transaction status
        Transaction txn = transferService.getTransaction(posted.transactionId()).orElseThrow();
        assertThat(txn.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(txn.amount()).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("REQ-007, REQ-008: Reject transfer with same account or invalid bounds")
    void testValidationConstraints() {
        UUID owner = createTestUser();
        Account acc = accountService.createAccount(owner, "INR");

        // Same account transfer
        assertThatThrownBy(() -> transferService.transfer(owner, UUID.randomUUID().toString(), acc.id(), acc.id(), 1000L, "INR"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SAME_ACCOUNT_TRANSFER);

        // Zero amount
        UUID otherAccId = UUID.randomUUID();
        assertThatThrownBy(() -> transferService.transfer(owner, UUID.randomUUID().toString(), acc.id(), otherAccId, 0L, "INR"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AMOUNT_OUT_OF_BOUNDS);
    }
}
