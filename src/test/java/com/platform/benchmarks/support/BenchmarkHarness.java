package com.platform.benchmarks.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class BenchmarkHarness {

    public static BenchmarkResult runBenchmark(
            String benchmarkName,
            String scenario,
            int concurrency,
            int poolSize,
            int warmupIterations,
            int measuredIterations,
            Supplier<Boolean> task,
            Map<String, Object> metadata
    ) throws InterruptedException {
        // 1. Warm-up
        for (int i = 0; i < warmupIterations; i++) {
            try {
                task.get();
            } catch (Exception ignored) {}
        }

        // 2. Measured run
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(measuredIterations);
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(measuredIterations));
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failureCount = new AtomicLong(0);

        for (int i = 0; i < measuredIterations; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    boolean ok = Boolean.TRUE.equals(task.get());
                    long duration = System.nanoTime() - start;
                    latenciesNanos.add(duration);
                    if (ok) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startTotal = System.nanoTime();
        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        long totalDurationNanos = System.nanoTime() - startTotal;

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        long totalDurationMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(totalDurationNanos));
        int totalFinished = latenciesNanos.size();
        double throughputTps = totalFinished > 0 ? (totalFinished * 1000.0) / totalDurationMs : 0.0;

        long[] sortedLatencies = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        double p50 = percentile(sortedLatencies, 0.50);
        double p95 = percentile(sortedLatencies, 0.95);
        double p99 = percentile(sortedLatencies, 0.99);
        double max = sortedLatencies.length > 0 ? sortedLatencies[sortedLatencies.length - 1] / 1_000_000.0 : 0.0;

        BenchmarkResult result = new BenchmarkResult(
                benchmarkName,
                scenario,
                concurrency,
                poolSize,
                warmupIterations,
                measuredIterations,
                totalDurationMs,
                Math.round(throughputTps * 10.0) / 10.0,
                Math.round(p50 * 100.0) / 100.0,
                Math.round(p95 * 100.0) / 100.0,
                Math.round(p99 * 100.0) / 100.0,
                Math.round(max * 100.0) / 100.0,
                successCount.get(),
                failureCount.get(),
                metadata
        );

        BenchmarkResultExporter.exportResult(result);
        return result;
    }

    private static double percentile(long[] sortedNanos, double pct) {
        if (sortedNanos.length == 0) return 0.0;
        int idx = (int) Math.ceil(pct * sortedNanos.length) - 1;
        idx = Math.max(0, Math.min(idx, sortedNanos.length - 1));
        return sortedNanos[idx] / 1_000_000.0; // convert to ms
    }
}
