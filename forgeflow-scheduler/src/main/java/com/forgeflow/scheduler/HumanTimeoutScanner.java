package com.forgeflow.scheduler;

import com.forgeflow.common.TaskEvent;
import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import com.forgeflow.store.TaskEventBus;
import com.forgeflow.store.TaskRepository;
import com.forgeflow.store.TenantQuotaStore;
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
 * Rejects tasks parked in WAITING_HUMAN longer than the configured timeout.
 *
 * <p>A waiting task still holds its tenant quota slot. Without this sweep, one abandoned task
 * permanently consumes capacity until someone looks at it.</p>
 */
@Component
@ConditionalOnProperty(name = "forgeflow.handoff.enabled", havingValue = "true", matchIfMissing = true)
public class HumanTimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(HumanTimeoutScanner.class);

    private final TaskRepository repository;
    private final TenantQuotaStore tenantQuota;
    private final TaskEventBus events;
    private final long timeoutMinutes;

    public HumanTimeoutScanner(TaskRepository repository, TenantQuotaStore tenantQuota,
                               TaskEventBus events,
                               @Value("${forgeflow.handoff.waiting-timeout-minutes:30}")
                               long timeoutMinutes) {
        this.repository = repository;
        this.tenantQuota = tenantQuota;
        this.events = events;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${forgeflow.handoff.scan-interval-ms:60000}")
    public void scan() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        List<TaskRecord> waiting = repository.findStale(
                List.of(TaskStatus.WAITING_HUMAN), threshold, 100);
        for (TaskRecord task : waiting) {
            String reason = "human timeout after " + timeoutMinutes + " minutes";
            if (repository.markRejectedByHuman(task.id(), task.version(), reason)) {
                tenantQuota.release(task.tenantId());
                events.publish(TaskEvent.status(task.id(), TaskStatus.FAILED, "rejected: human timeout"));
                log.warn("human timeout task={} tenant={} waited={}m",
                        task.id(), task.tenantId(), timeoutMinutes);
            }
        }
    }
}
