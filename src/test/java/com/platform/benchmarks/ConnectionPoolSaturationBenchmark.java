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

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionPoolSaturationBenchmark extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("Stage 5 Benchmark 5: DB Connection Pool Saturation vs Concurrency Contention")
    void runConnectionPoolBenchmark() throws Exception {
        UUID owner = createTestUser();
        Account src = accountService.createAccount(owner, "INR");
        Account dst = accountService.createAccount(owner, "INR");
        accountService.fundAccount(owner, UUID.randomUUID().toString(), src.id(), 10_000_000L);

        // 1. Moderate concurrency (5 threads, well within pool capacity)
        BenchmarkResult lowConcurrencyResult = BenchmarkHarness.runBenchmark(
                "ConnectionPoolSaturation",
                "LowConcurrencyWithinPool",
                5,
                50,
                10,
                100,
                () -> {
                    String idemKey = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, idemKey, src.id(), dst.id(), 10L, "INR");
                    return result instanceof TransferResult.Posted;
                },
                Map.of("description", "5 concurrent workers well within HikariCP maximum pool capacity (50 connections)")
        );

        // 2. High concurrency (30 threads contending for database connections and row locks)
        BenchmarkResult highConcurrencyResult = BenchmarkHarness.runBenchmark(
                "ConnectionPoolSaturation",
                "HighConcurrencyContention",
                30,
                50,
                10,
                150,
                () -> {
                    String idemKey = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, idemKey, src.id(), dst.id(), 10L, "INR");
                    return result instanceof TransferResult.Posted;
                },
                Map.of("description", "30 concurrent threads contending for row locks and connection leases")
        );

        System.out.printf("Low Concurrency (5 threads) TPS: %.2f | p99: %.2f ms%n",
                lowConcurrencyResult.throughputTps(), lowConcurrencyResult.p99LatencyMs());
        System.out.printf("High Concurrency (30 threads) TPS: %.2f | p99: %.2f ms%n",
                highConcurrencyResult.throughputTps(), highConcurrencyResult.p99LatencyMs());

        assertThat(lowConcurrencyResult.successCount()).isEqualTo(100);
        assertThat(highConcurrencyResult.successCount()).isEqualTo(150);
    }
}
