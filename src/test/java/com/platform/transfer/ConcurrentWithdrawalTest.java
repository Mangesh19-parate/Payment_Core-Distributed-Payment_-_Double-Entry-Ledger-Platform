package com.platform.transfer;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class ConcurrentWithdrawalTest extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("REQ-020, REQ-004: N concurrent withdrawals exceeding balance produce exactly floor(balance/amount) successes and 0 lost updates")
    void testConcurrentWithdrawalsExceedingBalance() throws InterruptedException {
        UUID ownerA = createTestUser();
        UUID ownerB = createTestUser();

        Account accountA = accountService.createAccount(ownerA, "INR");
        Account accountB = accountService.createAccount(ownerB, "INR");

        // Initial balance: 10,000 paise (₹100)
        long initialBalance = 10_000L;
        accountService.fundAccount(ownerA, UUID.randomUUID().toString(), accountA.id(), initialBalance);

        int totalThreads = 10;
        long withdrawalAmount = 3_000L; // floor(10000 / 3000) = 3 transfers should succeed (total 9000), remainder 1000

        int expectedSuccessCount = (int) (initialBalance / withdrawalAmount); // 3

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<TransferResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // ensure maximum contention
                    String key = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(
                            ownerA, key, accountA.id(), accountB.id(), withdrawalAmount, "INR"
                    );
                    results.add(result);
                    if (result instanceof TransferResult.Posted) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // unblock all threads simultaneously
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(expectedSuccessCount);
        assertThat(failureCount.get()).isEqualTo(totalThreads - expectedSuccessCount);

        // Verify balance consistency and zero lost updates
        Account finalA = accountService.getAccount(accountA.id());
        Account finalB = accountService.getAccount(accountB.id());

        long expectedFinalBalanceA = initialBalance - (expectedSuccessCount * withdrawalAmount);
        long expectedFinalBalanceB = expectedSuccessCount * withdrawalAmount;

        assertThat(finalA.cachedBalance()).isEqualTo(expectedFinalBalanceA);
        assertThat(finalB.cachedBalance()).isEqualTo(expectedFinalBalanceB);

        // Verify ledger matches cached balance perfectly (0 drift)
        AccountApplicationService.AccountBalanceSummary summaryA = accountService.getBalanceSummary(accountA.id());
        AccountApplicationService.AccountBalanceSummary summaryB = accountService.getBalanceSummary(accountB.id());

        assertThat(summaryA.delta()).isEqualTo(0L);
        assertThat(summaryB.delta()).isEqualTo(0L);
        assertThat(summaryA.ledgerDerivedBalance()).isEqualTo(expectedFinalBalanceA);
        assertThat(summaryB.ledgerDerivedBalance()).isEqualTo(expectedFinalBalanceB);
    }
}
