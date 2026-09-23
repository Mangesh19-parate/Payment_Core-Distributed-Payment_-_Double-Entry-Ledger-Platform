package com.platform.transfer;

import com.platform.BaseIntegrationTest;
import com.platform.account.domain.Account;
import com.platform.account.domain.AccountStatus;
import com.platform.account.persistence.AccountRepository;
import com.platform.common.error.BusinessException;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.Transaction;
import com.platform.transfer.domain.TransactionStatus;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionReversalTest extends BaseIntegrationTest {

    @Autowired
    private TransferApplicationService transferApplicationService;

    @Autowired
    private AccountRepository accountRepository;

    private static final UUID SYSTEM_CASH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Test
    @DisplayName("REQ-101: Transaction Reversal - authorization, state transitions, and structural single-reversal constraint")
    void testTransactionReversalFlow() {
        UUID initiatorId = createTestUser();
        UUID approverId = createTestUser();

        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();

        // 1. Setup accounts
        accountRepository.insertAccount(new Account(srcId, initiatorId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));
        accountRepository.insertAccount(new Account(dstId, approverId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));

        // Fund source account ₹5,000 (500,000 paise)
        transferApplicationService.transfer(initiatorId, "fund-rev-" + UUID.randomUUID(), SYSTEM_CASH_ID, srcId, 500_000L, "INR");

        // Execute original transfer of ₹2,000 (200,000 paise)
        TransferResult originalResult = transferApplicationService.transfer(
                initiatorId, "orig-tx-" + UUID.randomUUID(), srcId, dstId, 200_000L, "INR"
        );
        assertTrue(originalResult instanceof TransferResult.Posted);
        UUID originalTxnId = ((TransferResult.Posted) originalResult).transactionId();

        // Check balances: src=300,000, dst=200,000
        assertEquals(300_000L, accountRepository.findById(srcId).orElseThrow().cachedBalance());
        assertEquals(200_000L, accountRepository.findById(dstId).orElseThrow().cachedBalance());

        // 2. Initiator attempts to reverse their own transaction -> REJECTED (REQ-101)
        assertThrows(BusinessException.class, () ->
                transferApplicationService.reverseTransaction(initiatorId, "rev-self-" + UUID.randomUUID(), originalTxnId, "Fraud claim")
        );

        // 3. Authorized approver reverses transaction -> SUCCESS
        TransferResult revResult = transferApplicationService.reverseTransaction(
                approverId, "rev-valid-" + UUID.randomUUID(), originalTxnId, "Legitimate reversal"
        );
        assertTrue(revResult instanceof TransferResult.Posted);
        UUID reversalTxnId = ((TransferResult.Posted) revResult).transactionId();

        // 4. Verify state transitions
        Transaction originalTxn = transferApplicationService.getTransaction(originalTxnId).orElseThrow();
        assertEquals(TransactionStatus.REVERSED, originalTxn.status());

        Transaction reversalTxn = transferApplicationService.getTransaction(reversalTxnId).orElseThrow();
        assertEquals(TransactionStatus.POSTED, reversalTxn.status());
        assertEquals(originalTxnId, reversalTxn.referenceTxnId());

        // 5. Verify balances restored: src=500,000, dst=0
        assertEquals(500_000L, accountRepository.findById(srcId).orElseThrow().cachedBalance());
        assertEquals(0L, accountRepository.findById(dstId).orElseThrow().cachedBalance());

        // 6. Attempt second reversal on same original transaction -> Structurally blocked by partial unique index (REQ-101)
        assertThrows(Exception.class, () ->
                transferApplicationService.reverseTransaction(approverId, "rev-second-" + UUID.randomUUID(), originalTxnId, "Duplicate reversal attempt")
        );
    }
}
