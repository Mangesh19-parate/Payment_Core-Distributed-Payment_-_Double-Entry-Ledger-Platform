package com.platform.benchmarks.support;

import java.util.Map;

public record BenchmarkResult(
        String benchmarkName,
        String scenario,
        int concurrency,
        int poolSize,
        int warmupIterations,
        int measuredIterations,
        long totalDurationMs,
        double throughputTps,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double maxLatencyMs,
        long successCount,
        long failureCount,
        Map<String, Object> metadata
) {}
