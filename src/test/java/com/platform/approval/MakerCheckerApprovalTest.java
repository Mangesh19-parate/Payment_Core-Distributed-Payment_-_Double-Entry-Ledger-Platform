package com.platform.approval;

import com.platform.BaseIntegrationTest;
import com.platform.account.domain.Account;
import com.platform.account.domain.AccountStatus;
import com.platform.account.persistence.AccountRepository;
import com.platform.approval.application.ApprovalApplicationService;
import com.platform.approval.domain.TransactionApproval;
import com.platform.approval.persistence.ApprovalRepository;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
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

public class MakerCheckerApprovalTest extends BaseIntegrationTest {

    @Autowired
    private TransferApplicationService transferApplicationService;

    @Autowired
    private ApprovalApplicationService approvalApplicationService;

    @Autowired
    private com.platform.account.application.AccountApplicationService accountApplicationService;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private AccountRepository accountRepository;

    private static final UUID SYSTEM_CASH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Test
    @DisplayName("REQ-102: Maker-Checker approval flow for transfers above threshold")
    void testMakerCheckerFlow() {
        UUID makerId = createTestUser();
        UUID checkerId = createTestUser();

        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();

        // 1. Setup and fund accounts
        accountRepository.insertAccount(new Account(srcId, makerId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));
        accountRepository.insertAccount(new Account(dstId, checkerId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));

        accountApplicationService.fundAccount(makerId, "fund-mc-" + UUID.randomUUID(), srcId, 50_000_000L);

        // 2. Submit transfer above ₹1,00,000 threshold (e.g. 15,000,000 paise = ₹1,50,000)
        long largeAmountPaise = 15_000_000L;
        String idemKey = "large-tx-" + UUID.randomUUID();

        TransferResult result = transferApplicationService.transfer(
                makerId, idemKey, srcId, dstId, largeAmountPaise, "INR"
        );

        assertTrue(result instanceof TransferResult.AwaitingApproval);
        TransferResult.AwaitingApproval awaiting = (TransferResult.AwaitingApproval) result;
        UUID txnId = awaiting.transactionId();

        // 3. Verify transaction is AWAITING_APPROVAL in DB and no ledger entries written yet
        Transaction dbTxn = transferApplicationService.getTransaction(txnId).orElseThrow();
        assertEquals(TransactionStatus.AWAITING_APPROVAL, dbTxn.status());

        Integer ledgerEntryCount = testJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class,
                txnId
        );
        assertNotNull(ledgerEntryCount);
        assertEquals(0, ledgerEntryCount, "No ledger entries should exist while awaiting approval");

        // 4. Verify transaction_approvals record created
        TransactionApproval approval = approvalRepository.findByTransactionId(txnId).orElseThrow();
        assertEquals("PENDING", approval.status());
        assertEquals(makerId, approval.requestedBy());

        // 5. Maker attempts to approve own transfer -> must fail (REQ-102)
        assertThrows(BusinessException.class, () ->
                approvalApplicationService.approve(makerId, "approve-self-" + UUID.randomUUID(), txnId)
        );

        // 6. Distinct checker approves transfer -> successfully transitions to POSTED
        TransferResult approvedResult = approvalApplicationService.approve(
                checkerId, "approve-valid-" + UUID.randomUUID(), txnId
        );
        assertTrue(approvedResult instanceof TransferResult.Posted);

        Transaction finalTxn = transferApplicationService.getTransaction(txnId).orElseThrow();
        assertEquals(TransactionStatus.POSTED, finalTxn.status());

        // 7. Verify balanced ledger entries now exist
        Integer postApprovalLedgerCount = testJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class,
                txnId
        );
        assertNotNull(postApprovalLedgerCount);
        assertEquals(2, postApprovalLedgerCount, "Exactly 2 balanced ledger entries must exist after approval");

        // 8. Verify approval record is updated to APPROVED
        TransactionApproval resolvedApproval = approvalRepository.findByTransactionId(txnId).orElseThrow();
        assertEquals("APPROVED", resolvedApproval.status());
        assertEquals(checkerId, resolvedApproval.approvedBy());
    }

    @Test
    @DisplayName("REQ-102: Checker rejects awaiting-approval transfer")
    void testCheckerRejectionFlow() {
        UUID makerId = createTestUser();
        UUID checkerId = createTestUser();

        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();

        accountRepository.insertAccount(new Account(srcId, makerId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));
        accountRepository.insertAccount(new Account(dstId, checkerId, "INR", 0L, 0L, AccountStatus.ACTIVE, false, Instant.now()));
        accountApplicationService.fundAccount(makerId, "fund-mc-rej-" + UUID.randomUUID(), srcId, 50_000_000L);

        TransferResult result = transferApplicationService.transfer(
                makerId, "large-tx-rej-" + UUID.randomUUID(), srcId, dstId, 20_000_000L, "INR"
        );
        assertTrue(result instanceof TransferResult.AwaitingApproval);
        UUID txnId = ((TransferResult.AwaitingApproval) result).transactionId();

        // Checker rejects
        approvalApplicationService.reject(checkerId, "reject-key-" + UUID.randomUUID(), txnId, "Suspicious activity");

        Transaction dbTxn = transferApplicationService.getTransaction(txnId).orElseThrow();
        assertEquals(TransactionStatus.REJECTED, dbTxn.status());

        TransactionApproval approval = approvalRepository.findByTransactionId(txnId).orElseThrow();
        assertEquals("REJECTED", approval.status());
        assertEquals(checkerId, approval.approvedBy());
    }
}
