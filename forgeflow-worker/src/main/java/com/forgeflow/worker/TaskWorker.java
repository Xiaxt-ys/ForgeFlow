package com.forgeflow.worker;

import com.forgeflow.common.AgentExecutor;
import com.forgeflow.common.AgentRequest;
import com.forgeflow.common.AgentResult;
import com.forgeflow.common.ExecutionContext;
import com.forgeflow.common.TaskEvent;
import com.forgeflow.common.TaskMessage;
import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import com.forgeflow.common.trace.TaskTracer;
import com.forgeflow.common.trace.TraceContext;
import com.forgeflow.store.LeaseManager;
import com.forgeflow.store.TaskEventBus;
import com.forgeflow.store.TaskRepository;
import com.forgeflow.store.TenantQuotaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The worker is deliberately small: claim via version predicate, take a lease, run,
 * fence the result, release the tenant quota on terminal state.
 */
@Component
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);
    private static final long HEARTBEAT_SECONDS = 10;

    private final TaskRepository repository;
    private final LeaseManager leases;
    private final AgentExecutor executor;
    private final TaskTracer tracer;
    private final TenantQuotaStore tenantQuota;
    private final TaskEventBus events;
    private final String workerId;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ff-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public TaskWorker(TaskRepository repository, LeaseManager leases, AgentExecutor executor,
                      TaskTracer tracer, TenantQuotaStore tenantQuota, TaskEventBus events,
                      @Value("${forgeflow.worker.id:}") String configuredId) {
        this.repository = repository;
        this.leases = leases;
        this.executor = executor;
        this.tracer = tracer;
        this.tenantQuota = tenantQuota;
        this.events = events;
        this.workerId = configuredId == null || configuredId.isBlank()
                ? "worker-" + UUID.randomUUID()
                : configuredId;
    }

    public void handle(TaskMessage message) {
        TraceContext parent = tracer.decode(message.traceContext());
        TraceContext context = parent == null ? tracer.newTrace() : tracer.child(parent);
        TaskTracer.Span span = tracer.start("task.execute", context)
                .tag("taskId", message.taskId())
                .tag("attempt", message.attempt())
                .tag("workerId", workerId);
        try {
            handleInternal(message, context);
        } catch (RuntimeException failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.close();
        }
    }

    private void handleInternal(TaskMessage message, TraceContext trace) {
        String taskId = message.taskId();
        TaskRecord task;
        boolean claimed;

        // Lease first: a duplicate delivery must not bump the version of a task another
        // worker is running. Only the lease owner is allowed to touch the row.
        boolean acquired;
        try (TaskTracer.Span leaseSpan = tracer.start("lease.acquire", tracer.child(trace))
                .tag("taskId", taskId).tag("workerId", workerId)) {
            acquired = leases.tryAcquire(taskId, workerId, LEASE_TTL);
            leaseSpan.tag("acquired", acquired);
        }
        if (!acquired) {
            log.info("lease held by another worker task={}", taskId);
            return;
        }

        try (TaskTracer.Span claimSpan = tracer.start("task.claim", tracer.child(trace))
                .tag("taskId", taskId)) {
            task = repository.findById(taskId).orElse(null);
            if (task == null || task.terminal()) {
                claimSpan.tag("skipped", task == null ? "not-found" : "terminal");
                leases.release(taskId, workerId);
                return;
            }
            claimed = task.status() == TaskStatus.QUEUED
                    ? repository.tryClaim(taskId, task.version(), workerId)
                    : repository.tryReclaim(taskId, task.version(), workerId);
            claimSpan.tag("claimed", claimed).tag("fromStatus", task.status().name());
        }
        if (!claimed) {
            log.info("claim lost task={} status={} version={}", taskId, task.status(), task.version());
            leases.release(taskId, workerId);
            return;
        }

        AtomicBoolean leaseLost = new AtomicBoolean(false);
        AtomicBoolean quotaReleased = new AtomicBoolean(false);
        ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
            if (!leases.renew(taskId, workerId, LEASE_TTL)) {
                leaseLost.set(true);
                log.warn("lease renewal failed task={} worker={} traceId={}",
                        taskId, workerId, trace.traceId());
            } else {
                // Keep the row fresh: a live long task must not look stale to the recovery scan.
                // A transient DB error here must not kill the heartbeat schedule.
                try {
                    repository.touch(taskId);
                } catch (RuntimeException failure) {
                    log.warn("heartbeat touch failed task={}: {}", taskId, failure.getMessage());
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        try {
            TaskRecord claimedTask = repository.getById(taskId);
            if (!repository.markRunning(taskId, claimedTask.version())) {
                log.warn("mark running lost task={} version={}", taskId, claimedTask.version());
                return;
            }
            TaskRecord running = repository.getById(taskId);
            events.publish(TaskEvent.status(taskId, TaskStatus.RUNNING, "worker " + workerId));
            String tenantId = running.tenantId();
            ExecutionContext executionContext = () -> leaseLost.get()
                    || repository.findById(taskId)
                    .map(current -> current.status() == TaskStatus.CANCELLING)
                    .orElse(true);

            AgentResult result;
            try (TaskTracer.Span agentSpan = tracer.start("agent.execute", tracer.child(trace))
                    .tag("taskId", taskId)
                    .tag("executor", executor.getClass().getSimpleName())) {
                result = executor.execute(
                        new AgentRequest(taskId, running.tenantId(), running.prompt()), executionContext);
                agentSpan.tag("steps", result.steps())
                        .tag("promptTokens", result.promptTokens())
                        .tag("completionTokens", result.completionTokens());
            }

            TaskRecord latest = repository.getById(taskId);
            if (leaseLost.get()) {
                log.warn("stale worker refuses to commit task={} version={}", taskId, latest.version());
                return;
            }
            try (TaskTracer.Span commitSpan = tracer.start("task.commit", tracer.child(trace))
                    .tag("taskId", taskId).tag("fromStatus", latest.status().name())) {
                if (latest.status() == TaskStatus.CANCELLING) {
                    commitSpan.tag("toStatus", "CANCELLED");
                    if (repository.markCancelled(taskId, latest.version())) {
                        releaseQuota(tenantId, quotaReleased);
                        events.publish(TaskEvent.status(taskId, TaskStatus.CANCELLED, "cancelled"));
                    }
                    return;
                }
                boolean committed = repository.markSucceeded(taskId, latest.version(), result.output());
                if (committed) {
                    releaseQuota(tenantId, quotaReleased);
                    events.publish(TaskEvent.status(taskId, TaskStatus.SUCCEEDED, "completed"));
                }
                commitSpan.tag("toStatus", "SUCCEEDED").tag("committed", committed);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            TaskRecord latest = repository.getById(taskId);
            if (latest.status() == TaskStatus.CANCELLING) {
                if (repository.markCancelled(taskId, latest.version())) {
                    releaseQuota(latest.tenantId(), quotaReleased);
                    events.publish(TaskEvent.status(taskId, TaskStatus.CANCELLED, "cancelled"));
                }
            } else if (!latest.terminal()) {
                if (repository.markFailed(taskId, latest.version(), "INTERRUPTED",
                        interrupted.getMessage() == null ? "interrupted" : interrupted.getMessage())) {
                    releaseQuota(latest.tenantId(), quotaReleased);
                    events.publish(TaskEvent.status(taskId, TaskStatus.FAILED, "interrupted"));
                }
            }
        } catch (Exception failure) {
            TaskRecord latest = repository.getById(taskId);
            if (latest.status() != TaskStatus.CANCELLING
                    && failure instanceof ApprovalPendingException pending
                    && repository.markWaitingApproval(taskId, latest.version(),
                    pending.getMessage())) {
                log.warn("task parked for approval task={} tool={}", taskId, pending.tool());
                events.publish(TaskEvent.status(taskId, TaskStatus.WAITING_APPROVAL,
                        pending.getMessage()));
                return;
            }
            if (latest.status() != TaskStatus.CANCELLING
                    && failure instanceof HumanHandoffRequiredException handoff
                    && repository.markWaitingHuman(taskId, latest.version(), handoff.getMessage())) {
                log.warn("task parked for human task={} reason={}", taskId, handoff.getMessage());
                events.publish(TaskEvent.status(taskId, TaskStatus.WAITING_HUMAN, handoff.getMessage()));
                return;
            }
            log.error("task execution failed task={}", taskId, failure);
            if (latest.status() == TaskStatus.CANCELLING) {
                if (repository.markCancelled(taskId, latest.version())) {
                    releaseQuota(latest.tenantId(), quotaReleased);
                    events.publish(TaskEvent.status(taskId, TaskStatus.CANCELLED, "cancelled"));
                }
            } else if (!latest.terminal()) {
                if (repository.markFailed(taskId, latest.version(),
                        "AGENT_EXECUTION_FAILED",
                        failure.getMessage() == null ? failure.getClass().getSimpleName()
                                : failure.getMessage())) {
                    releaseQuota(latest.tenantId(), quotaReleased);
                    events.publish(TaskEvent.status(taskId, TaskStatus.FAILED,
                            failure.getMessage() == null ? "failed" : failure.getMessage()));
                }
            }
        } finally {
            renewal.cancel(false);
            leases.release(taskId, workerId);
        }
    }

    private void releaseQuota(String tenantId, AtomicBoolean released) {
        if (tenantId != null && released.compareAndSet(false, true)) {
            tenantQuota.release(tenantId);
        }
    }
}
