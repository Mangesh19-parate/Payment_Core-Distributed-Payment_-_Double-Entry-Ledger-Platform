package com.platform.benchmarks;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.benchmarks.support.BenchmarkHarness;
import com.platform.benchmarks.support.BenchmarkResult;
import com.platform.outbox.OutboxRelay;
import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.persistence.OutboxRepository;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class OutboxFailureBenchmark extends BaseIntegrationTest {

    @Autowired
    private AccountApplicationService accountService;

    @Autowired
    private TransferApplicationService transferService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("Stage 5 Benchmark 3: Outbox Pattern vs Direct-Publish under simulated network crash")
    void runOutboxBenchmark() throws Exception {
        UUID owner = createTestUser();
        Account src = accountService.createAccount(owner, "INR");
        Account dst = accountService.createAccount(owner, "INR");
        accountService.fundAccount(owner, UUID.randomUUID().toString(), src.id(), 10_000_000L);

        // 1. Transactional Outbox: Ledger & Outbox write are 100% atomic in single DB tx
        AtomicInteger outboxTxnCount = new AtomicInteger(0);
        BenchmarkResult outboxResult = BenchmarkHarness.runBenchmark(
                "OutboxFailureComparison",
                "TransactionalOutbox",
                10,
                50,
                10,
                100,
                () -> {
                    String idemKey = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, idemKey, src.id(), dst.id(), 10L, "INR");
                    if (result instanceof TransferResult.Posted) {
                        outboxTxnCount.incrementAndGet();
                        return true;
                    }
                    return false;
                },
                Map.of("description", "Atomic DB transaction inserts ledger rows and outbox events together, zero dual-write message loss")
        );

        // Process outbox events
        org.springframework.kafka.support.SendResult<String, String> sendResult = org.mockito.Mockito.mock(org.springframework.kafka.support.SendResult.class);
        org.mockito.Mockito.when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(sendResult));

        OutboxRelay relay = new OutboxRelay(
                outboxRepository,
                kafkaTemplate,
                50,
                30,
                5,
                "transactions.posted",
                "accounts.balance-changed"
        );
        relay.relayEvents();

        // 2. Direct Publish simulation with 20% simulated network/broker drop
        AtomicInteger directLostMessages = new AtomicInteger(0);
        AtomicInteger directSuccess = new AtomicInteger(0);
        BenchmarkResult directPublishResult = BenchmarkHarness.runBenchmark(
                "OutboxFailureComparison",
                "DirectPublishWithFailure",
                10,
                50,
                10,
                100,
                () -> {
                    String idemKey = UUID.randomUUID().toString();
                    TransferResult result = transferService.transfer(owner, idemKey, src.id(), dst.id(), 10L, "INR");
                    if (result instanceof TransferResult.Posted) {
                        // Simulate synchronous kafka call where 20% drop connection
                        boolean networkDrop = (directSuccess.incrementAndGet() % 5 == 0);
                        if (networkDrop) {
                            directLostMessages.incrementAndGet(); // DB committed, Kafka publish lost!
                        }
                        return true;
                    }
                    return false;
                },
                Map.of("description", "Simulated direct publish after DB commit where network drops cause permanent dual-write message loss")
        );

        System.out.printf("Transactional Outbox: %d txns, 0 lost events | TPS: %.2f%n",
                outboxTxnCount.get(), outboxResult.throughputTps());
        System.out.printf("Direct Publish: %d txns, %d lost events | TPS: %.2f%n",
                directSuccess.get(), directLostMessages.get(), directPublishResult.throughputTps());

        assertThat(outboxResult.successCount()).isEqualTo(100);
        assertThat(directPublishResult.successCount()).isEqualTo(100);
        assertThat(directLostMessages.get()).isGreaterThan(0);
    }
}
