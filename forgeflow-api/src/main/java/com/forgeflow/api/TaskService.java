package com.forgeflow.api;

import com.forgeflow.common.InvalidTransitionException;
import com.forgeflow.common.ResolutionCommand;
import com.forgeflow.common.SubmitTaskCommand;
import com.forgeflow.common.TaskDispatcher;
import com.forgeflow.common.TaskEvent;
import com.forgeflow.common.TaskMessage;
import com.forgeflow.common.TaskNotFoundException;
import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import com.forgeflow.common.TaskView;
import com.forgeflow.common.TenantQuotaExceededException;
import com.forgeflow.common.trace.TaskTracer;
import com.forgeflow.common.trace.TraceContext;
import com.forgeflow.store.ApprovalRecord;
import com.forgeflow.store.ApprovalRepository;
import com.forgeflow.store.SessionMemoryStore;
import com.forgeflow.store.TaskEventBus;
import com.forgeflow.store.TaskOutboxRepository;
import com.forgeflow.store.TaskRepository;
import com.forgeflow.store.TenantQuotaStore;
import com.forgeflow.worker.tool.ApprovalToken;
import com.forgeflow.worker.tool.ApprovalTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository repository;
    private final TaskOutboxRepository outbox;
    private final TaskDispatcher dispatcher;
    private final TaskTracer tracer;
    private final TenantQuotaStore tenantQuota;
    private final TaskEventBus events;
    private final ApprovalRepository approvals;
    private final ApprovalTokenStore approvalTokens;
    private final SessionMemoryStore sessions;

    public TaskService(TaskRepository repository, TaskOutboxRepository outbox,
                       TaskDispatcher dispatcher, TaskTracer tracer,
                       TenantQuotaStore tenantQuota, TaskEventBus events,
                       ApprovalRepository approvals, ApprovalTokenStore approvalTokens,
                       SessionMemoryStore sessions) {
        this.repository = repository;
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.tracer = tracer;
        this.tenantQuota = tenantQuota;
        this.events = events;
        this.approvals = approvals;
        this.approvalTokens = approvalTokens;
        this.sessions = sessions;
    }

    @Transactional
    public TaskView submit(SubmitTaskCommand command, String idempotencyKey) {
        TraceContext trace = tracer.newTrace();
        try (TaskTracer.Span ignored = tracer.start("task.submit", trace)) {
            return submitInternal(command, idempotencyKey, trace);
        }
    }

    private TaskView submitInternal(SubmitTaskCommand command, String idempotencyKey,
                                    TraceContext trace) {
        String key = normalize(idempotencyKey);
        if (key != null) {
            var existing = repository.findByIdempotencyKey(command.tenantId(), key);
            if (existing.isPresent()) {
                return TaskView.from(existing.get());
            }
        }

        String sessionId = command.sessionId() == null || command.sessionId().isBlank()
                ? null
                : command.sessionId().trim();
        if (sessionId != null) {
            sessions.ensureSession(sessionId, command.tenantId());
        }

        if (!tenantQuota.tryAcquire(command.tenantId())) {
            throw new TenantQuotaExceededException(command.tenantId());
        }

        Instant now = Instant.now();
        TaskRecord task = new TaskRecord(
                UUID.randomUUID().toString(),
                command.tenantId(),
                TaskStatus.QUEUED,
                1L,
                null,
                key,
                command.prompt(),
                null,
                null,
                null,
                now,
                now);
        try {
            repository.insert(task);
        } catch (DuplicateKeyException duplicate) {
            tenantQuota.release(command.tenantId());
            // The unique (tenant_id, idempotency_key) index is the final idempotency fence.
            return repository.findByIdempotencyKey(command.tenantId(), key)
                    .map(TaskView::from)
                    .orElseThrow(() -> duplicate);
        } catch (RuntimeException failure) {
            tenantQuota.release(command.tenantId());
            throw failure;
        }

        // Task row + outbox row commit together. Dispatch after commit; if it fails,
        // OutboxRelay re-publishes the same task. Workers are idempotent, so duplicates are safe.
        if (sessionId != null) {
            sessions.bindTask(task.id(), sessionId);
        }
        String traceContext = tracer.encode(trace);
        outbox.insert(task.id(), traceContext);
        dispatchAfterCommit(task.id(), traceContext);
        return TaskView.from(task);
    }

    private void dispatchAfterCommit(String taskId, String traceContext) {
        Runnable dispatch = () -> {
            events.publish(TaskEvent.status(taskId, TaskStatus.QUEUED, "submitted"));
            try {
                dispatcher.dispatch(new TaskMessage(taskId, 0, traceContext));
                outbox.markSentByTaskId(taskId);
            } catch (Exception failure) {
                // Leave the outbox row NEW; the relay retries with backoff.
                log.warn("direct dispatch failed task={}, outbox relay will retry: {}",
                        taskId, failure.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    public TaskView get(String id) {
        try (TaskTracer.Span ignored = tracer.start("task.get", tracer.newTrace())) {
            return TaskView.from(repository.getById(id));
        }
    }

    public TaskView cancel(String id) {
        try (TaskTracer.Span ignored = tracer.start("task.cancel", tracer.newTrace())) {
            return cancelInternal(id);
        }
    }

    private TaskView cancelInternal(String id) {
        TaskRecord task = repository.getById(id);
        if (task.terminal()) {
            return TaskView.from(task);
        }
        if (!repository.markCancelling(id)) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.CANCELLING);
        }
        TaskView view = get(id);
        events.publish(TaskEvent.status(id, view.status(), "cancel requested"));
        if (view.status() == TaskStatus.CANCELLED) {
            // QUEUED tasks cancel immediately and no worker will release their quota.
            tenantQuota.release(task.tenantId());
        }
        return view;
    }

    public TaskView result(String id) {
        return get(id);
    }

    /**
     * Grants approval for the tool call a task is parked on: issues a capability token bound
     * to the exact turn/tool/args, then re-queues the task so a worker resumes the turn.
     */
    @Transactional
    public ApprovalDecision approve(String id) {
        TaskRecord task = repository.getById(id);
        if (task.status() != TaskStatus.WAITING_APPROVAL) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.WAITING_APPROVAL);
        }
        ApprovalRecord pending = approvals.findPending(id)
                .orElseThrow(() -> new InvalidTransitionException(id, task.status(),
                        TaskStatus.WAITING_APPROVAL));
        long ttlSeconds = Duration.ofMinutes(10).toSeconds();
        ApprovalToken token = approvalTokens.issue(id, pending.turnId(), pending.tool(),
                pending.argsHash(), Duration.ofSeconds(ttlSeconds));
        approvals.markApproved(pending.id());
        if (!repository.resumeFromApproval(id, task.version())) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.QUEUED);
        }
        dispatchAfterCommit(id, tracer.encode(tracer.newTrace()));
        events.publish(TaskEvent.status(id, TaskStatus.QUEUED, "approval granted"));
        return new ApprovalDecision(token.value(), ttlSeconds);
    }

    /** Denies the pending tool call and fails the task with APPROVAL_REJECTED. */
    @Transactional
    public TaskView rejectApproval(String id, String reason) {
        TaskRecord task = repository.getById(id);
        if (task.status() != TaskStatus.WAITING_APPROVAL) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.WAITING_APPROVAL);
        }
        approvals.findPending(id).ifPresent(pending -> approvals.markRejected(pending.id()));
        String message = reason == null || reason.isBlank() ? "approval rejected" : reason;
        if (!repository.markApprovalRejected(id, task.version(), message)) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.FAILED);
        }
        tenantQuota.release(task.tenantId());
        events.publish(TaskEvent.status(id, TaskStatus.FAILED, "approval rejected"));
        return get(id);
    }

    public record ApprovalDecision(String token, long expiresInSeconds) {
    }

    /**
     * Resolves a task parked in WAITING_HUMAN: REJECT fails it, RESUME re-queues it with the
     * human answer appended to the prompt.
     */
    @Transactional
    public TaskView resolve(String id, ResolutionCommand command) {
        TaskRecord task = repository.getById(id);
        if (task.status() != TaskStatus.WAITING_HUMAN) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.WAITING_HUMAN);
        }
        String decision = command.decision() == null ? "" : command.decision().trim().toUpperCase();
        if ("REJECT".equals(decision)) {
            if (!repository.markRejectedByHuman(id, task.version(), command.answer())) {
                throw new InvalidTransitionException(id, task.status(), TaskStatus.FAILED);
            }
            tenantQuota.release(task.tenantId());
            events.publish(TaskEvent.status(id, TaskStatus.FAILED, "rejected by human"));
            return get(id);
        }
        if (!"RESUME".equals(decision)) {
            throw new IllegalArgumentException("decision must be RESUME or REJECT");
        }
        String prompt = task.prompt();
        if (command.answer() != null && !command.answer().isBlank()) {
            prompt = prompt + "\n[human] " + command.answer().trim();
        }
        if (!repository.resumeFromHuman(id, task.version(), prompt)) {
            throw new InvalidTransitionException(id, task.status(), TaskStatus.QUEUED);
        }
        dispatchAfterCommit(id, tracer.encode(tracer.newTrace()));
        return get(id);
    }

    private String normalize(String key) {
        return key == null || key.isBlank() ? null : key.trim();
    }
}
