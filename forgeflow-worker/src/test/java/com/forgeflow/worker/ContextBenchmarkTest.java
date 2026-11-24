package com.forgeflow.worker;

import com.forgeflow.worker.memory.ContextBenchmark;
import com.forgeflow.worker.memory.ContextBenchmarkReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBenchmarkTest {

    @TempDir
    Path workspace;

    @Test
    void compressionReducesTokensWithoutLosingKeyFacts() {
        ContextBenchmark benchmark = new ContextBenchmark(ContextBenchmark.assembler(workspace));
        ContextBenchmarkReport report = benchmark.run(30, 3, 2_000);

        System.out.println(report);
        assertThat(report.compressedTokens()).isLessThan(report.baselineTokens());
        assertThat(report.tokenReduction()).isGreaterThan(0.30);
        assertThat(report.baselineCompletion()).isEqualTo(1.0);
        assertThat(report.compressedCompletion()).isGreaterThanOrEqualTo(0.98);
    }
}
