package com.platform.benchmarks;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.benchmarks.support.BenchmarkHarness;
import com.platform.benchmarks.support.BenchmarkResult;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class IdempotencyMechanismBenchmark extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("Stage 5 Benchmark 2: Idempotency Mechanism comparison (Naive SELECT-check vs DB ON CONFLICT)")
    void runIdempotencyBenchmark() throws Exception {
        UUID owner = createTestUser();
        Account src = accountService.createAccount(owner, "INR");
        Account dst = accountService.createAccount(owner, "INR");
        accountService.fundAccount(owner, UUID.randomUUID().toString(), src.id(), 10_000_000L);

        // 1. Core ON CONFLICT mechanism under 50% duplicate burst
        String[] sharedKeys = new String[20];
        for (int i = 0; i < sharedKeys.length; i++) {
            sharedKeys[i] = UUID.randomUUID().toString();
        }

        AtomicInteger reqCounter = new AtomicInteger(0);
        BenchmarkResult onConflictResult = BenchmarkHarness.runBenchmark(
                "IdempotencyMechanism",
                "DbOnConflictIdempotency",
                10,
                50,
                10,
                200,
                () -> {
                    int c = reqCounter.getAndIncrement();
                    // 50% chance of repeating an existing key
                    String key = (c % 2 == 0) ? sharedKeys[(c / 2) % sharedKeys.length] : UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, key, src.id(), dst.id(), 5L, "INR");
                    return (result instanceof TransferResult.Posted) || (result instanceof TransferResult.IdempotentReplay);
                },
                Map.of("description", "Atomic DB INSERT ON CONFLICT DO NOTHING with SAVEPOINT recovery under high duplicate contention")
        );

        // 2. Naive Application Level Pre-Check
        AtomicInteger naiveCounter = new AtomicInteger(0);
        BenchmarkResult naiveResult = BenchmarkHarness.runBenchmark(
                "IdempotencyMechanism",
                "NaiveSelectCheckIdempotency",
                10,
                50,
                10,
                200,
                () -> {
                    int c = naiveCounter.getAndIncrement();
                    String key = (c % 2 == 0) ? sharedKeys[(c / 2) % sharedKeys.length] : UUID.randomUUID().toString();

                    // Simulating naive app check: check if row exists via SELECT first
                    Integer count = testJdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM transactions WHERE principal_id = ? AND idempotency_key = ?",
                            Integer.class,
                            owner, key
                    );
                    if (count != null && count > 0) {
                        return true; // cached hit
                    }
                    TransferResult result = transferService.transfer(owner, key, src.id(), dst.id(), 5L, "INR");
                    return (result instanceof TransferResult.Posted) || (result instanceof TransferResult.IdempotentReplay);
                },
                Map.of("description", "Non-atomic application SELECT query before proceeding with transfer, susceptible to race conditions and extra roundtrips")
        );

        System.out.printf("DB ON CONFLICT TPS: %.2f | p99: %.2f ms%n", onConflictResult.throughputTps(), onConflictResult.p99LatencyMs());
        System.out.printf("Naive SELECT Check TPS: %.2f | p99: %.2f ms%n", naiveResult.throughputTps(), naiveResult.p99LatencyMs());

        assertThat(onConflictResult.successCount()).isEqualTo(200);
        assertThat(naiveResult.successCount()).isEqualTo(200);
    }
}
