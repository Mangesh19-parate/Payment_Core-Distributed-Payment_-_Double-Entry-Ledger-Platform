package com.platform.transfer;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.transfer.domain.EntryType;
import com.platform.transfer.domain.LedgerEntry;
import com.platform.transfer.domain.TransactionStatus;
import com.platform.transfer.domain.TransactionType;
import com.platform.transfer.persistence.LedgerRepository;
import com.platform.transfer.persistence.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LedgerBalancePropertyTest extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("REQ-002: trg_ledger_balance trigger prevents committing imbalanced entries (SUM(DEBIT) != SUM(CREDIT))")
    void testTriggerRejectsImbalancedLedgerEntries() {
        UUID owner = createTestUser();
        Account accA = accountService.createAccount(owner, "INR");
        Account accB = accountService.createAccount(owner, "INR");

        UUID txnId = UUID.randomUUID();

        // Attempt to insert and commit an imbalanced ledger (Debit 1000, Credit 800)
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            transactionRepository.tryInsertInitialTransaction(
                    txnId, owner, "idem-" + txnId, "hash", accA.id(), accB.id(), 1000L, "INR", TransactionType.TRANSFER
            );

            LedgerEntry debit = new LedgerEntry(null, txnId, accA.id(), EntryType.DEBIT, 1000L, "INR", Instant.now());
            LedgerEntry credit = new LedgerEntry(null, txnId, accB.id(), EntryType.CREDIT, 800L, "INR", Instant.now()); // Imbalance of 200

            ledgerRepository.insertEntries(List.of(debit, credit));
            return null; // commit triggers check_ledger_balance()
        })).hasRootCauseMessage("ERROR: Ledger imbalance for transaction " + txnId + ": 200\n  Where: PL/pgSQL function check_ledger_balance() line 7 at RAISE");
    }

    @Test
    @DisplayName("REQ-003: trg_posting_invariant trigger prevents marking a transaction POSTED without >= 2 ledger entries")
    void testTriggerRejectsPostingWithoutEntries() {
        UUID owner = createTestUser();
        Account accA = accountService.createAccount(owner, "INR");
        Account accB = accountService.createAccount(owner, "INR");

        UUID txnId = UUID.randomUUID();

        // Attempt to update status to POSTED without creating any ledger entries
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            transactionRepository.tryInsertInitialTransaction(
                    txnId, owner, "idem-" + txnId, "hash", accA.id(), accB.id(), 1000L, "INR", TransactionType.TRANSFER
            );

            // Directly update status to POSTED without ledger entries
            transactionRepository.updateStatus(txnId, TransactionStatus.POSTED, null, Instant.now());
            return null; // commit triggers check_posting_invariant()
        })).hasRootCauseMessage("ERROR: Transaction " + txnId + " marked POSTED with 0 ledger entries\n  Where: PL/pgSQL function check_posting_invariant() line 7 at RAISE");
    }

    @Test
    @DisplayName("REQ-002, REQ-006: Balanced entries commit successfully without trigger violations")
    void testBalancedEntriesCommitSuccessfully() {
        UUID owner = createTestUser();
        Account accA = accountService.createAccount(owner, "INR");
        Account accB = accountService.createAccount(owner, "INR");

        UUID txnId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            transactionRepository.tryInsertInitialTransaction(
                    txnId, owner, "idem-" + txnId, "hash", accA.id(), accB.id(), 2500L, "INR", TransactionType.TRANSFER
            );

            LedgerEntry debit = new LedgerEntry(null, txnId, accA.id(), EntryType.DEBIT, 2500L, "INR", Instant.now());
            LedgerEntry credit = new LedgerEntry(null, txnId, accB.id(), EntryType.CREDIT, 2500L, "INR", Instant.now());

            ledgerRepository.insertEntries(List.of(debit, credit));
            transactionRepository.updateStatus(txnId, TransactionStatus.POSTED, null, Instant.now());
            return null;
        });

        List<LedgerEntry> entries = ledgerRepository.findByTransactionId(txnId);
        assertThat(entries).hasSize(2);
    }
}
