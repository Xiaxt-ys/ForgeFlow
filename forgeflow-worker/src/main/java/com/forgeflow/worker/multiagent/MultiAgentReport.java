package com.forgeflow.worker.multiagent;

public record MultiAgentReport(int rounds, int subtasksPerRound, int concurrency,
                               int total, int claimed, int executed, int succeeded,
                               int duplicates, int omissions, int rejectedAttempts,
                               long elapsedMs) {
}
