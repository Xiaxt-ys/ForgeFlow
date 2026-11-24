package com.forgeflow.scheduler;

import com.forgeflow.store.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Memory decay: without a sweep, session memory only grows.
 *
 * <ul>
 *   <li>stale — a fact older than {@code retention-days} with fewer than {@code min-hits}
 *       recalls and below {@code max-importance} is archived (never deleted, so it can be
 *       inspected or revived);</li>
 *   <li>overflow — a session keeps at most {@code max-facts-per-session} active facts, ranked
 *       by importance then recency.</li>
 * </ul>
 *
 * <p>Reinforcement is what makes this safe: every fact that was actually injected into a prompt
 * gets {@code hits++}, so facts the entry agent keeps using never decay just because time passed.
 * A fact only dies when it is old, unused and unimportant.</p>
 */
@Component
@ConditionalOnProperty(name = "forgeflow.memory.decay.enabled", havingValue = "true",
        matchIfMissing = true)
public class MemoryDecayScanner {

    private static final Logger log = LoggerFactory.getLogger(MemoryDecayScanner.class);

    private final SessionRepository repository;
    private final int retentionDays;
    private final int minHits;
    private final double maxImportance;
    private final int maxFactsPerSession;

    public MemoryDecayScanner(SessionRepository repository,
                              @Value("${forgeflow.memory.decay.retention-days:7}") int retentionDays,
                              @Value("${forgeflow.memory.decay.min-hits:2}") int minHits,
                              @Value("${forgeflow.memory.decay.max-importance:0.8}")
                              double maxImportance,
                              @Value("${forgeflow.memory.decay.max-facts-per-session:200}")
                              int maxFactsPerSession) {
        this.repository = repository;
        this.retentionDays = Math.max(1, retentionDays);
        this.minHits = Math.max(0, minHits);
        this.maxImportance = Math.max(0, Math.min(1, maxImportance));
        this.maxFactsPerSession = Math.max(1, maxFactsPerSession);
    }

    @Scheduled(fixedDelayString = "${forgeflow.memory.decay.scan-interval-ms:600000}",
            initialDelayString = "${forgeflow.memory.decay.initial-delay-ms:60000}")
    public void scan() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int stale = repository.archiveStale(cutoff, minHits, maxImportance);
        int overflow = 0;
        List<String> sessions = repository.allSessionIds(1000);
        for (String sessionId : sessions) {
            overflow += repository.archiveOverflow(sessionId, maxFactsPerSession);
        }
        if (stale + overflow > 0) {
            log.info("memory.decay archived_stale={} archived_overflow={} sessions={}",
                    stale, overflow, sessions.size());
        }
    }
}
