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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class HotAccountContentionBenchmark extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Test
    @DisplayName("Stage 5 Benchmark 1: Hot-account vs Independent-account contention")
    void runHotVsIndependentBenchmark() throws Exception {
        UUID owner = createTestUser();

        // 1. Setup Hot Account Scenario
        Account hotSource = accountService.createAccount(owner, "INR");
        Account hotDest = accountService.createAccount(owner, "INR");
        accountService.fundAccount(owner, UUID.randomUUID().toString(), hotSource.id(), 10_000_000L);

        BenchmarkResult hotResult = BenchmarkHarness.runBenchmark(
                "HotAccountContention",
                "HotSingleAccountPair",
                10,
                50,
                20,
                200,
                () -> {
                    String idemKey = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, idemKey, hotSource.id(), hotDest.id(), 10L, "INR");
                    return result instanceof TransferResult.Posted;
                },
                Map.of("description", "All 10 concurrent threads target the exact same 2 accounts, causing sequential lock queues")
        );

        // 2. Setup Independent Accounts Scenario
        int numPairs = 200;
        List<Account> sources = new ArrayList<>(numPairs);
        List<Account> dests = new ArrayList<>(numPairs);
        for (int i = 0; i < numPairs; i++) {
            Account src = accountService.createAccount(owner, "INR");
            Account dst = accountService.createAccount(owner, "INR");
            accountService.fundAccount(owner, UUID.randomUUID().toString(), src.id(), 500_000L);
            sources.add(src);
            dests.add(dst);
        }

        AtomicInteger indexCounter = new AtomicInteger(0);
        BenchmarkResult independentResult = BenchmarkHarness.runBenchmark(
                "HotAccountContention",
                "IndependentAccountPairs",
                10,
                50,
                20,
                200,
                () -> {
                    int idx = Math.abs(indexCounter.getAndIncrement() % numPairs);
                    String idemKey = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, idemKey, sources.get(idx).id(), dests.get(idx).id(), 10L, "INR");
                    return result instanceof TransferResult.Posted;
                },
                Map.of("description", "10 concurrent threads target disjoint independent account pairs")
        );

        System.out.printf("Hot Account TPS: %.2f | p99: %.2f ms%n", hotResult.throughputTps(), hotResult.p99LatencyMs());
        System.out.printf("Independent Accounts TPS: %.2f | p99: %.2f ms%n", independentResult.throughputTps(), independentResult.p99LatencyMs());

        assertThat(hotResult.successCount()).isEqualTo(200);
        assertThat(independentResult.successCount()).isEqualTo(200);
    }
}
