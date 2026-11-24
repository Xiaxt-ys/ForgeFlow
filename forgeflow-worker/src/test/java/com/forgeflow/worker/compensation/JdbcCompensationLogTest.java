package com.forgeflow.worker.compensation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Sql(scripts = "/schema.sql")
class JdbcCompensationLogTest {

    @Autowired
    JdbcTemplate jdbc;

    private final List<String> undone = new ArrayList<>();
    private JdbcCompensationLog log;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ff_compensation");
        jdbc.update("DELETE FROM ff_task");
        Map<String, Compensator> compensators = Map.of(
                "write_note", (taskId, tool, args) -> new Compensation(
                        tool, "remove note " + args.get("note"),
                        () -> undone.add("undo-" + args.get("note")), args));
        log = new JdbcCompensationLog(jdbc, new ObjectMapper(), compensators);
    }

    private static Compensation note(String value) {
        return new Compensation("write_note", "remove note " + value, () -> {
        }, Map.of("note", value));
    }

    @Test
    void recordsAndRunsUndoInReverseOrder() {
        log.record("t1", note("a"));
        log.record("t1", note("b"));

        assertThat(log.size("t1")).isEqualTo(2);

        List<String> done = log.compensateAll("t1");

        assertThat(undone).containsExactly("undo-b", "undo-a");
        assertThat(done).containsExactly("write_note: remove note b", "write_note: remove note a");
        assertThat(log.size("t1")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ff_compensation WHERE task_id = 't1' AND status = 'DONE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void missingCompensatorIsMarkedFailedInsteadOfSilentlyDropped() {
        log.record("t1", new Compensation("delete", "remove file", () -> {
        }, Map.of("path", "a.txt")));

        List<String> done = log.compensateAll("t1");

        assertThat(done).containsExactly("delete: no compensator");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ff_compensation WHERE task_id = 't1' AND status = 'FAILED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void clearDropsPendingRows() {
        log.record("t1", note("a"));

        log.clear("t1");

        assertThat(log.size("t1")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ff_compensation", Integer.class)).isZero();
    }

    @Test
    void retryHandsTheSameOperationIdToTheUndo() {
        List<String> seenIds = new ArrayList<>();
        Compensator recording = new Compensator() {
            @Override
            public Compensation compensation(String taskId, String tool, Map<String, Object> args) {
                return compensation(taskId, null, tool, args);
            }

            @Override
            public Compensation compensation(String taskId, String operationId, String tool,
                                             Map<String, Object> args) {
                seenIds.add(operationId);
                return new Compensation(operationId, tool, "undo", () -> {
                }, args);
            }
        };
        JdbcCompensationLog retryLog = new JdbcCompensationLog(
                jdbc, new ObjectMapper(), Map.of("write_note", recording));
        retryLog.record("t1", note("a"));
        retryLog.compensateAll("t1");

        // Simulate a crash after the undo ran but before the row was marked DONE.
        jdbc.update("UPDATE ff_compensation SET status = 'PENDING' WHERE task_id = 't1'");
        retryLog.compensateAll("t1");

        assertThat(seenIds).hasSize(2);
        assertThat(seenIds.get(0)).isNotBlank();
        assertThat(seenIds.get(0)).isEqualTo(seenIds.get(1));
        assertThat(jdbc.queryForObject(
                "SELECT operation_id FROM ff_compensation WHERE task_id = 't1'", String.class))
                .isEqualTo(seenIds.get(0));
    }

    @Test
    void recoverStaleCompensatesForTerminalTasksLeftBehind() {
        insertTask("t1", "FAILED");
        log.record("t1", note("a"));
        jdbc.update("UPDATE ff_compensation SET created_at = ? WHERE task_id = 't1'",
                Timestamp.from(Instant.now().minusSeconds(300)));

        log.recoverStale();

        assertThat(undone).containsExactly("undo-a");
        assertThat(log.size("t1")).isZero();
    }

    private void insertTask(String id, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                        INSERT INTO ff_task (id, tenant_id, status, version, prompt, created_at, updated_at)
                        VALUES (?, 'tenant-a', ?, 1, 'p', ?, ?)
                        """,
                id, status, now, now);
    }
}
