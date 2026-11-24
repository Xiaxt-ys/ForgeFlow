package com.forgeflow.scheduler;

import com.forgeflow.common.TaskDispatcher;
import com.forgeflow.common.TaskMessage;
import com.forgeflow.store.TaskOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Publishes outbox rows that were committed but not delivered.
 * At-least-once is fine: the worker's claim/version predicate is the idempotency fence.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final TaskOutboxRepository outbox;
    private final TaskDispatcher dispatcher;

    public OutboxRelay(TaskOutboxRepository outbox, TaskDispatcher dispatcher) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${forgeflow.outbox.relay-interval-ms:1000}")
    public void relay() {
        List<TaskOutboxRepository.OutboxRecord> pending = outbox.findPending(100);
        for (TaskOutboxRepository.OutboxRecord record : pending) {
            try {
                dispatcher.dispatch(new TaskMessage(record.taskId(), record.attempts(),
                        record.traceContext()));
                outbox.markSent(record.id());
            } catch (Exception failure) {
                int attempts = record.attempts() + 1;
                long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
                outbox.markFailed(record.id(), attempts, Instant.now().plusSeconds(delaySeconds));
                log.warn("outbox publish failed task={} attempt={}, retry in {}s: {}",
                        record.taskId(), attempts, delaySeconds, failure.getMessage());
            }
        }
    }
}
