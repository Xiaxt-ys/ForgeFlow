package com.forgeflow.worker.memory;

public record ContextBenchmarkReport(int tasks, int runs,
                                     long baselineTokens, long compressedTokens,
                                     double tokenReduction,
                                     double baselineCompletion, double compressedCompletion) {

    @Override
    public String toString() {
        return "ContextBenchmarkReport{" +
                "tasks=" + tasks + ", runs=" + runs +
                ", baselineTokens=" + baselineTokens +
                ", compressedTokens=" + compressedTokens +
                ", tokenReduction=" + String.format("%.1f%%", tokenReduction * 100) +
                ", baselineCompletion=" + String.format("%.1f%%", baselineCompletion * 100) +
                ", compressedCompletion=" + String.format("%.1f%%", compressedCompletion * 100) +
                '}';
    }
}
