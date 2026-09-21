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
import com.platform.transfer.persistence.LedgerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IdempotencyRaceTest extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Test
    @DisplayName("REQ-023, REQ-024: Concurrent requests with same Idempotency-Key result in exactly 1 post and 0 duplicates")
    void testConcurrentIdempotentRequests() throws InterruptedException {
        UUID ownerA = createTestUser();
        UUID ownerB = createTestUser();

        Account accountA = accountService.createAccount(ownerA, "INR");
        Account accountB = accountService.createAccount(ownerB, "INR");

        accountService.fundAccount(ownerA, UUID.randomUUID().toString(), accountA.id(), 10_000L);

        String sharedKey = "idem-race-" + UUID.randomUUID();
        int callers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        CountDownLatch doneLatch = new CountDownLatch(callers);

        AtomicInteger postedCount = new AtomicInteger(0);
        AtomicInteger replayedCount = new AtomicInteger(0);
        List<UUID> transactionIds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < callers; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    TransferResult result = transferService.transfer(
                            ownerA, sharedKey, accountA.id(), accountB.id(), 1_000L, "INR"
                    );
                    if (result instanceof TransferResult.Posted p) {
                        postedCount.incrementAndGet();
                        transactionIds.add(p.transactionId());
                    } else if (result instanceof TransferResult.IdempotentReplay r) {
                        replayedCount.incrementAndGet();
                        transactionIds.add(r.transactionId());
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(postedCount.get()).isEqualTo(1);
        assertThat(replayedCount.get()).isEqualTo(callers - 1);

        // All returned the same transaction ID
        UUID distinctTxnId = transactionIds.get(0);
        assertThat(transactionIds).allMatch(id -> id.equals(distinctTxnId));

        // Exactly 2 ledger entries created for this transaction
        List<LedgerEntry> entries = ledgerRepository.findByTransactionId(distinctTxnId);
        assertThat(entries).hasSize(2);

        // Balances correctly reflect single 1,000 paise transfer
        assertThat(accountService.getAccount(accountA.id()).cachedBalance()).isEqualTo(9_000L);
        assertThat(accountService.getAccount(accountB.id()).cachedBalance()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("REQ-024: Same idempotency key with different payload triggers 409 Conflict")
    void testIdempotencyKeyPayloadConflict() {
        UUID owner = createTestUser();
        Account accA = accountService.createAccount(owner, "INR");
        Account accB = accountService.createAccount(owner, "INR");
        Account accC = accountService.createAccount(owner, "INR");

        accountService.fundAccount(owner, UUID.randomUUID().toString(), accA.id(), 10_000L);

        String sharedKey = "conflict-test-" + UUID.randomUUID();

        // Initial transfer: A -> B for 1000
        TransferResult firstResult = transferService.transfer(
                owner, sharedKey, accA.id(), accB.id(), 1_000L, "INR"
        );
        assertThat(firstResult).isInstanceOf(TransferResult.Posted.class);

        // Attempt transfer with same key but different destination: A -> C for 1000
        assertThatThrownBy(() -> transferService.transfer(owner, sharedKey, accA.id(), accC.id(), 1_000L, "INR"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("REQ-025: Business failure is durably recorded against idempotency key and replayable")
    void testBusinessFailureDurableIdempotency() {
        UUID owner = createTestUser();
        Account accA = accountService.createAccount(owner, "INR");
        Account accB = accountService.createAccount(owner, "INR");

        // No funds in accA
        String failureKey = "fail-key-" + UUID.randomUUID();

        TransferResult firstResult = transferService.transfer(
                owner, failureKey, accA.id(), accB.id(), 5_000L, "INR"
        );
        assertThat(firstResult).isInstanceOf(TransferResult.BusinessFailure.class);

        // Replay with same key
        TransferResult replayResult = transferService.transfer(
                owner, failureKey, accA.id(), accB.id(), 5_000L, "INR"
        );
        assertThat(replayResult).isInstanceOf(TransferResult.IdempotentReplay.class);
        TransferResult.IdempotentReplay replay = (TransferResult.IdempotentReplay) replayResult;
        assertThat(replay.status()).isEqualTo(TransactionStatus.FAILED);
    }
}
