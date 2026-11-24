package com.forgeflow.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable record of a suspended tool call. The turn itself lives in agents-flex's turn
 * store; this row is what lets the API find the pending call and what the worker checks on
 * re-execution to decide between resuming and running fresh.
 */
@Repository
public class ApprovalRepository {

    private static final RowMapper<ApprovalRecord> MAPPER = (rs, rowNum) -> new ApprovalRecord(
            rs.getString("id"),
            rs.getString("task_id"),
            rs.getString("turn_id"),
            rs.getString("call_id"),
            rs.getString("tool"),
            rs.getString("args_hash"),
            rs.getString("args_json"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public ApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApprovalRecord insert(String taskId, String turnId, String callId, String tool,
                                 String argsHash, String argsJson) {
        ApprovalRecord record = new ApprovalRecord(UUID.randomUUID().toString(), taskId, turnId,
                callId, tool, argsHash, argsJson, "PENDING");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                        INSERT INTO ff_approval
                            (id, task_id, turn_id, call_id, tool, args_hash, args_json,
                             status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                record.id(), taskId, turnId, callId, tool, argsHash, argsJson,
                record.status(), now, now);
        return record;
    }

    public Optional<ApprovalRecord> findPending(String taskId) {
        return findLatest(taskId, "PENDING");
    }

    public Optional<ApprovalRecord> findApproved(String taskId) {
        return findLatest(taskId, "APPROVED");
    }

    public boolean markApproved(String id) {
        return updateStatus(id, "APPROVED");
    }

    public boolean markRejected(String id) {
        return updateStatus(id, "REJECTED");
    }

    public void deleteByTask(String taskId) {
        jdbc.update("DELETE FROM ff_approval WHERE task_id = ?", taskId);
    }

    public List<ApprovalRecord> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM ff_approval WHERE task_id = ? ORDER BY created_at DESC",
                MAPPER, taskId);
    }

    private Optional<ApprovalRecord> findLatest(String taskId, String status) {
        List<ApprovalRecord> rows = jdbc.query("""
                        SELECT * FROM ff_approval
                         WHERE task_id = ? AND status = ?
                         ORDER BY created_at DESC LIMIT 1
                        """,
                MAPPER, taskId, status);
        return rows.stream().findFirst();
    }

    private boolean updateStatus(String id, String status) {
        int rows = jdbc.update(
                "UPDATE ff_approval SET status = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                status, Timestamp.from(Instant.now()), id);
        return rows == 1;
    }
}
