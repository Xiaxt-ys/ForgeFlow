package com.forgeflow.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox for task dispatch. The task row and this row are committed together;
 * a relay can always re-publish a task that was committed but never reached the broker.
 */
@Repository
public class TaskOutboxRepository {

    private static final RowMapper<OutboxRecord> MAPPER = TaskOutboxRepository::map;

    private final JdbcTemplate jdbc;

    public TaskOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String taskId, String traceContext) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO ff_outbox (id, task_id, event_type, payload, status, attempts,
                                       next_retry_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), taskId, "TASK_CREATED",
                traceContext == null ? "" : traceContext,
                "NEW", 0, Timestamp.from(now), Timestamp.from(now));
    }

    public List<OutboxRecord> findPending(int limit) {
        // Compare against a Java-side clock: `CURRENT_TIMESTAMP` uses the MySQL session
        // timezone, which can differ from the zone the driver used when writing the row
        // (observed as an 8-hour gap and a relay that never fires). The 1s grace absorbs
        // precision skew between the JVM and the database clock.
        return jdbc.query("""
                SELECT id, task_id, payload, attempts FROM ff_outbox
                 WHERE status = 'NEW' AND next_retry_at <= ?
                 ORDER BY id LIMIT ?
                """, MAPPER, Timestamp.from(Instant.now().plusSeconds(1)), limit);
    }

    public void markSent(String id) {
        jdbc.update("UPDATE ff_outbox SET status = 'SENT' WHERE id = ?", id);
    }

    public void markSentByTaskId(String taskId) {
        jdbc.update("UPDATE ff_outbox SET status = 'SENT' WHERE task_id = ? AND status = 'NEW'", taskId);
    }

    public void markFailed(String id, int attempts, Instant nextRetryAt) {
        jdbc.update("""
                UPDATE ff_outbox
                   SET status = 'NEW', attempts = ?, next_retry_at = ?
                 WHERE id = ?
                """, attempts, Timestamp.from(nextRetryAt), id);
    }

    private static OutboxRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRecord(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("payload"),
                rs.getInt("attempts"));
    }

    public record OutboxRecord(String id, String taskId, String traceContext, int attempts) {
    }
}
