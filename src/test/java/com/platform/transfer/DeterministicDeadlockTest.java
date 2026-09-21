package com.platform.transfer;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class DeterministicDeadlockTest extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("REQ-020: Opposing-direction transfers (A->B and B->A) execute concurrently without deadlocking due to ascending ID lock ordering")
    void testOpposingTransfersNoDeadlock() throws InterruptedException {
        UUID ownerA = createTestUser();
        UUID ownerB = createTestUser();

        Account accountA = accountService.createAccount(ownerA, "INR");
        Account accountB = accountService.createAccount(ownerB, "INR");

        // Fund both accounts with 50,000 paise
        accountService.fundAccount(ownerA, UUID.randomUUID().toString(), accountA.id(), 50_000L);
        accountService.fundAccount(ownerB, UUID.randomUUID().toString(), accountB.id(), 50_000L);

        int pairs = 5; // 5 threads A->B and 5 threads B->A (10 opposing threads)
        int totalThreads = pairs * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger deadlockOrTimeoutCount = new AtomicInteger(0);

        for (int i = 0; i < pairs; i++) {
            // A -> B transfer
            executor.submit(() -> {
                try {
                    barrier.await();
                    TransferResult res = transferService.transfer(
                            ownerA, UUID.randomUUID().toString(), accountA.id(), accountB.id(), 100L, "INR"
                    );
                    if (res instanceof TransferResult.Posted) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    deadlockOrTimeoutCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            // B -> A transfer (opposing direction)
            executor.submit(() -> {
                try {
                    barrier.await();
                    TransferResult res = transferService.transfer(
                            ownerB, UUID.randomUUID().toString(), accountB.id(), accountA.id(), 100L, "INR"
                    );
                    if (res instanceof TransferResult.Posted) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    deadlockOrTimeoutCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finishedInTime = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finishedInTime).isTrue();
        assertThat(deadlockOrTimeoutCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(totalThreads);

        // Sum of all money across accounts must remain exactly 100,000 paise
        Account finalA = accountService.getAccount(accountA.id());
        Account finalB = accountService.getAccount(accountB.id());

        assertThat(finalA.cachedBalance() + finalB.cachedBalance()).isEqualTo(100_000L);
    }
}
