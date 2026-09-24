package com.platform.benchmarks;

import com.platform.BaseIntegrationTest;
import com.platform.benchmarks.support.BenchmarkHarness;
import com.platform.benchmarks.support.BenchmarkResult;
import com.platform.velocity.VelocityCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class RedisVelocityBenchmark extends BaseIntegrationTest {

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
    @DisplayName("Stage 5 Benchmark 4: Redis Velocity Variant Comparison (Atomic Lua Script vs Multi-step Client Roundtrips)")
    void runRedisVelocityBenchmark() throws Exception {
        UUID accountIdLua = UUID.randomUUID();
        UUID accountIdMulti = UUID.randomUUID();

        // 1. Atomic Lua Script
        AtomicInteger luaCounter = new AtomicInteger(0);
        BenchmarkResult luaResult = BenchmarkHarness.runBenchmark(
                "RedisVelocityVariants",
                "AtomicLuaScript",
                10,
                50,
                50,
                500,
                () -> {
                    int c = luaCounter.incrementAndGet();
                    try {
                        velocityCheckService.checkAndRecord(accountIdLua, 100L, "tx-lua-" + c);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                },
                Map.of("description", "Single-roundtrip Redis Lua script executing slide window trim, atomic monetary sum, and conditional insert")
        );

        // 2. Multi-step Client Roundtrips (simulating naive non-Lua check with 3 client-server roundtrips)
        AtomicInteger multiCounter = new AtomicInteger(0);
        BenchmarkResult multiResult = BenchmarkHarness.runBenchmark(
                "RedisVelocityVariants",
                "MultiStepClientRoundtrips",
                10,
                50,
                50,
                500,
                () -> {
                    int c = multiCounter.incrementAndGet();
                    String key = "velocity:manual:" + accountIdMulti;
                    long now = Instant.now().toEpochMilli();
                    long windowStart = now - 3600_000L;

                    // Roundtrip 1: Trim old entries
                    stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

                    // Roundtrip 2: Fetch range to compute sum in client JVM
                    Set<ZSetOperations.TypedTuple<String>> entries = stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
                    long currentSum = 0;
                    if (entries != null) {
                        for (var entry : entries) {
                            String val = entry.getValue();
                            if (val != null && val.contains(":")) {
                                currentSum += Long.parseLong(val.split(":")[0]);
                            }
                        }
                    }

                    // Roundtrip 3: Add new entry
                    if (currentSum + 100L <= 10_000_000_000L) {
                        stringRedisTemplate.opsForZSet().add(key, "100:tx-manual-" + c, now);
                        return true;
                    }
                    return false;
                },
                Map.of("description", "Naive multi-step client approach: 3 network roundtrips (trim, fetch & client sum, add) prone to race conditions")
        );

        System.out.printf("Atomic Lua Script TPS: %.2f | p99: %.2f ms%n", luaResult.throughputTps(), luaResult.p99LatencyMs());
        System.out.printf("Multi-step Roundtrips TPS: %.2f | p99: %.2f ms%n", multiResult.throughputTps(), multiResult.p99LatencyMs());

        assertThat(luaResult.successCount()).isGreaterThan(0);
        assertThat(multiResult.successCount()).isGreaterThan(0);
    }
}
