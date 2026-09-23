package com.platform.velocity;

import com.platform.BaseIntegrationTest;
import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class VelocityCheckServiceTest extends BaseIntegrationTest {

    @Autowired
    private VelocityCheckService velocityCheckService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void cleanRedis() {
        var connection = stringRedisTemplate.getConnectionFactory().getConnection();
        connection.serverCommands().flushDb();
    }

    @Test
    @DisplayName("REQ-060: Velocity check sums monetary amounts within window, not event count")
    void testMonetarySumVelocityLimit() {
        UUID accountId = UUID.randomUUID();
        long limitPaise = 50_000_000L; // ₹5,00,000

        // Perform 4 smaller transfers summing to 40,000,000 paise (allowed)
        for (int i = 0; i < 4; i++) {
            velocityCheckService.checkAndRecord(accountId, 10_000_000L, "tx-" + i);
        }

        // Attempt transfer of 15,000,000 paise -> total 55,000,000 > limit 50,000,000 (must be rejected)
        BusinessException ex = assertThrows(BusinessException.class, () ->
                velocityCheckService.checkAndRecord(accountId, 15_000_000L, "tx-overflow")
        );

        assertEquals(ErrorCode.VELOCITY_LIMIT_EXCEEDED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("exceeds limit"));
    }

    @Test
    @DisplayName("REQ-061: Atomic concurrent velocity requests enforce limit without over-allocation")
    void testConcurrentVelocityChecking() throws InterruptedException {
        UUID accountId = UUID.randomUUID();
        long limitPaise = 50_000_000L; // 500,000 INR
        long transferAmount = 10_000_000L; // Each is 100,000 INR -> exactly 5 should succeed

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String txId = "tx-concurrent-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    velocityCheckService.checkAndRecord(accountId, transferAmount, txId);
                    successCount.incrementAndGet();
                } catch (BusinessException be) {
                    if (be.getErrorCode() == ErrorCode.VELOCITY_LIMIT_EXCEEDED) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        assertEquals(5, successCount.get(), "Exactly 5 transfers of 10M paise should fit in 50M limit");
        assertEquals(5, rejectedCount.get(), "Remaining 5 transfers must be rejected by atomic Lua check");
    }
}
