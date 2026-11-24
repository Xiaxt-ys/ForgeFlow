package com.forgeflow.store;

import com.forgeflow.common.TaskNotFoundException;
import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * All task mutations are conditional updates. A caller that loses the race gets {@code false}
 * and must not proceed; this is the database half of ForgeFlow's fencing.
 */
@Repository
public class TaskRepository {

    private static final RowMapper<TaskRecord> MAPPER = TaskRepository::map;

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TaskRecord task) {
        jdbc.update("""
                INSERT INTO ff_task
                (id, tenant_id, status, version, worker_id, idempotency_key, prompt,
                 result, error_code, error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(), task.tenantId(), task.status().name(), task.version(), task.workerId(),
                task.idempotencyKey(), task.prompt(), task.result(), task.errorCode(), task.errorMessage(),
                Timestamp.from(task.createdAt()), Timestamp.from(task.updatedAt()));
    }

    public Optional<TaskRecord> findById(String id) {
        List<TaskRecord> rows = jdbc.query("SELECT * FROM ff_task WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public TaskRecord getById(String id) {
        return findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    public Optional<TaskRecord> findByIdempotencyKey(String tenantId, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        List<TaskRecord> rows = jdbc.query(
                "SELECT * FROM ff_task WHERE tenant_id = ? AND idempotency_key = ?",
                MAPPER, tenantId, key);
        return rows.stream().findFirst();
    }

    /**
     * QUEUED -> CLAIMED. The version predicate is the fence: a stale worker cannot claim twice.
     */
    public boolean tryClaim(String id, long expectedVersion, String workerId) {
        Instant now = Instant.now();
        int rows = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, worker_id = ?, updated_at = ?
                 WHERE id = ? AND status = ? AND version = ?
                """,
                TaskStatus.CLAIMED.name(), workerId, Timestamp.from(now),
                id, TaskStatus.QUEUED.name(), expectedVersion);
        return rows == 1;
    }

    /**
     * A recovery dispatch may target a task left in CLAIMED/RUNNING by a dead worker.
     * The lease still decides who runs; this update only moves the state back to CLAIMED.
     */
    public boolean tryReclaim(String id, long expectedVersion, String workerId) {
        int rows = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, worker_id = ?, updated_at = ?
                 WHERE id = ? AND status IN (?, ?) AND version = ?
                """,
                TaskStatus.CLAIMED.name(), workerId, Timestamp.from(Instant.now()),
                id, TaskStatus.CLAIMED.name(), TaskStatus.RUNNING.name(), expectedVersion);
        return rows == 1;
    }

    public boolean markRunning(String id, long expectedVersion) {
        return transition(id, TaskStatus.CLAIMED, TaskStatus.RUNNING, expectedVersion,
                null, null, null);
    }

    /**
     * Heartbeat: keeps {@code updated_at} fresh while a lease is being renewed, so the recovery
     * scanner can tell "long task, still alive" from "worker disappeared". Without this a task
     * running longer than the stale window looks abandoned and is re-dispatched every scan.
     */
    public void touch(String id) {
        jdbc.update("""
                        UPDATE ff_task
                           SET updated_at = ?
                         WHERE id = ? AND status IN (?, ?)
                        """,
                Timestamp.from(Instant.now()), id,
                TaskStatus.CLAIMED.name(), TaskStatus.RUNNING.name());
    }

    public boolean markSucceeded(String id, long expectedVersion, String result) {
        return transition(id, TaskStatus.RUNNING, TaskStatus.SUCCEEDED, expectedVersion,
                result, null, null);
    }

    public boolean markFailed(String id, long expectedVersion, String errorCode, String errorMessage) {
        TaskRecord current = getById(id);
        TaskStatus from = current.status() == TaskStatus.CLAIMED ? TaskStatus.CLAIMED : TaskStatus.RUNNING;
        return transition(id, from, TaskStatus.FAILED, expectedVersion, null, errorCode, errorMessage);
    }

    public boolean markCancelling(String id) {
        Instant now = Instant.now();
        // A queued task has no worker; cancel it directly.
        int queued = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE id = ? AND status IN (?, ?, ?)
                """,
                TaskStatus.CANCELLED.name(), Timestamp.from(now), id,
                TaskStatus.QUEUED.name(), TaskStatus.WAITING_HUMAN.name(),
                TaskStatus.WAITING_APPROVAL.name());
        if (queued == 1) {
            return true;
        }
        int rows = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE id = ? AND status IN (?, ?, ?)
                """,
                TaskStatus.CANCELLING.name(), Timestamp.from(now), id,
                TaskStatus.CLAIMED.name(), TaskStatus.RUNNING.name(),
                TaskStatus.WAITING_APPROVAL.name());
        return rows == 1;
    }

    public boolean markCancelled(String id, long expectedVersion) {
        return transition(id, TaskStatus.CANCELLING, TaskStatus.CANCELLED, expectedVersion,
                null, null, null);
    }

    /** A running task could not finish; park it for a human instead of failing it. */
    public boolean markWaitingHuman(String id, long expectedVersion, String reason) {
        return transition(id, TaskStatus.RUNNING, TaskStatus.WAITING_HUMAN, expectedVersion,
                null, "WAITING_HUMAN", reason);
    }

    /** Human rejected the parked task. */
    public boolean markRejectedByHuman(String id, long expectedVersion, String reason) {
        return transition(id, TaskStatus.WAITING_HUMAN, TaskStatus.FAILED, expectedVersion,
                null, "HUMAN_REJECTED", reason);
    }

    /** A tool call needs approval: park the task instead of failing the turn. */
    public boolean markWaitingApproval(String id, long expectedVersion, String reason) {
        return transition(id, TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL, expectedVersion,
                null, "WAITING_APPROVAL", reason);
    }

    /** Approval granted: put the task back in the queue so a worker resumes the turn. */
    public boolean resumeFromApproval(String id, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, error_code = NULL,
                       error_message = NULL, updated_at = ?
                 WHERE id = ? AND status = ? AND version = ?
                """,
                TaskStatus.QUEUED.name(), Timestamp.from(Instant.now()),
                id, TaskStatus.WAITING_APPROVAL.name(), expectedVersion);
        return rows == 1;
    }

    /** Approval denied: the task fails with a dedicated error code. */
    public boolean markApprovalRejected(String id, long expectedVersion, String reason) {
        return transition(id, TaskStatus.WAITING_APPROVAL, TaskStatus.FAILED, expectedVersion,
                null, "APPROVAL_REJECTED", reason);
    }

    /** Human answered: put the task back in the queue with the augmented prompt. */
    public boolean resumeFromHuman(String id, long expectedVersion, String prompt) {
        int rows = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, prompt = ?, error_code = NULL,
                       error_message = NULL, updated_at = ?
                 WHERE id = ? AND status = ? AND version = ?
                """,
                TaskStatus.QUEUED.name(), prompt, Timestamp.from(Instant.now()),
                id, TaskStatus.WAITING_HUMAN.name(), expectedVersion);
        return rows == 1;
    }

    public List<TaskRecord> findStale(List<TaskStatus> statuses, Instant olderThan, int limit) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        Object[] args = new Object[statuses.size() + 2];
        int i = 0;
        for (TaskStatus status : statuses) {
            args[i++] = status.name();
        }
        args[i++] = Timestamp.from(olderThan);
        args[i] = limit;
        return jdbc.query("SELECT * FROM ff_task WHERE status IN (" + placeholders
                        + ") AND updated_at < ? ORDER BY updated_at LIMIT ?",
                MAPPER, args);
    }

    public long countByStatus(TaskStatus status) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM ff_task WHERE status = ?",
                Long.class, status.name());
        return value == null ? 0 : value;
    }

    private boolean transition(String id, TaskStatus from, TaskStatus to, long expectedVersion,
                               String result, String errorCode, String errorMessage) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("illegal transition " + from + " -> " + to);
        }
        int rows = jdbc.update("""
                UPDATE ff_task
                   SET status = ?, version = version + 1, result = ?, error_code = ?,
                       error_message = ?, updated_at = ?
                 WHERE id = ? AND status = ? AND version = ?
                """,
                to.name(), result, errorCode, errorMessage, Timestamp.from(Instant.now()),
                id, from.name(), expectedVersion);
        return rows == 1;
    }

    private static TaskRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRecord(
                rs.getString("id"),
                rs.getString("tenant_id"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getString("worker_id"),
                rs.getString("idempotency_key"),
                rs.getString("prompt"),
                rs.getString("result"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
