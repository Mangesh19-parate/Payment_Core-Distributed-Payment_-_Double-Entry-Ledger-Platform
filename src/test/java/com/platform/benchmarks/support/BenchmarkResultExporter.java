package com.platform.benchmarks.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;

public class BenchmarkResultExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String TARGET_DIR = "target/benchmark-results";

    public static synchronized void exportResult(BenchmarkResult result) {
        try {
            File dir = new File(TARGET_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String filename = result.benchmarkName().toLowerCase() + "_" + result.scenario().toLowerCase().replaceAll("[^a-z0-9]", "_") + ".json";
            File outputFile = new File(dir, filename);
            MAPPER.writeValue(outputFile, result);
            System.out.printf("[BENCHMARK-OUTPUT] Exported %s to %s%n", result.benchmarkName(), outputFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to export benchmark result: " + e.getMessage());
        }
    }
}
