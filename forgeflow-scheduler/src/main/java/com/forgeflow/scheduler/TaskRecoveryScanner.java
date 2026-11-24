package com.forgeflow.scheduler;

import com.forgeflow.common.TaskDispatcher;
import com.forgeflow.common.TaskMessage;
import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import com.forgeflow.store.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-dispatches tasks whose worker disappeared. The DB version predicate on claim means a
 * resurrected old worker cannot commit after the new one starts.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class TaskRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryScanner.class);
    private static final Duration STALE_AFTER = Duration.ofSeconds(30);

    private final TaskRepository repository;
    private final TaskDispatcher dispatcher;

    public TaskRecoveryScanner(TaskRepository repository, TaskDispatcher dispatcher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${forgeflow.recovery.scan-interval-ms:5000}")
    public void scan() {
        Instant threshold = Instant.now().minus(STALE_AFTER);
        List<TaskRecord> stale = repository.findStale(
                List.of(TaskStatus.CLAIMED, TaskStatus.RUNNING), threshold, 100);
        for (TaskRecord task : stale) {
            log.warn("re-dispatch stale task id={} status={} version={} worker={}",
                    task.id(), task.status(), task.version(), task.workerId());
            dispatcher.dispatch(new TaskMessage(task.id(), 1));
        }
    }
}
