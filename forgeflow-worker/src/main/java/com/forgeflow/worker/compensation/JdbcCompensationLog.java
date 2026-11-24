package com.forgeflow.worker.compensation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeflow.worker.tools.LocalDemoTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable compensation log.
 *
 * <p>Every undo action is written before the turn continues, so a worker crash no longer
 * loses it. A scheduled pass finishes compensations for tasks that ended in FAILED/CANCELLED
 * while the worker was gone.</p>
 *
 * <p>Undo actions are at-least-once: make them idempotent, or key them by task and action id.</p>
 */
@Component
@ConditionalOnProperty(prefix = "forgeflow.compensation", name = "mode", havingValue = "jdbc")
public class JdbcCompensationLog implements CompensationTracker {

    private static final Logger log = LoggerFactory.getLogger(JdbcCompensationLog.class);
    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Map<String, Compensator> compensators;
    private final AtomicLong seq;

    @Autowired
    public JdbcCompensationLog(JdbcTemplate jdbc, ObjectMapper mapper,
                               ObjectProvider<LocalDemoTools> localTools) {
        this(jdbc, mapper, localTools.getIfAvailable() == null
                ? Map.of() : localTools.getIfAvailable().compensators());
    }

    JdbcCompensationLog(JdbcTemplate jdbc, ObjectMapper mapper,
                        Map<String, Compensator> compensators) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.compensators = Map.copyOf(compensators);
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM ff_compensation", Long.class);
        this.seq = new AtomicLong(max == null ? 0L : max);
    }

    @Override
    public void record(String taskId, Compensation compensation) {
        if (taskId == null || compensation == null) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                        INSERT INTO ff_compensation
                            (id, task_id, operation_id, seq, tool, args_json, description,
                             status, attempts, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(), taskId, compensation.operationId(),
                seq.incrementAndGet(), compensation.tool(), writeArgs(compensation.args()),
                compensation.description(), "PENDING", 0, now, now);
        log.info("compensation.recorded task={} op={} tool={} action={}",
                taskId, compensation.operationId(), compensation.tool(), compensation.description());
    }

    @Override
    public List<String> compensateAll(String taskId) {
        if (taskId == null) {
            return List.of();
        }
        List<Pending> pending = jdbc.query("""
                        SELECT id, operation_id, tool, args_json, description
                          FROM ff_compensation
                         WHERE task_id = ? AND status = 'PENDING'
                         ORDER BY seq DESC
                        """,
                (rs, rowNum) -> new Pending(rs.getString("id"), rs.getString("operation_id"),
                        rs.getString("tool"), rs.getString("args_json"),
                        rs.getString("description")),
                taskId);
        if (pending.isEmpty()) {
            return List.of();
        }

        List<String> done = new ArrayList<>(pending.size());
        for (Pending action : pending) {
            // Claim the row first: a concurrent worker or the recovery scan must not run it twice.
            if (!claim(action.id())) {
                continue;
            }
            String label = action.tool() + ": " + action.description();
            try {
                Compensator compensator = compensators.get(action.tool());
                if (compensator == null) {
                    mark(action.id(), "FAILED");
                    done.add(action.tool() + ": no compensator");
                    continue;
                }
                Compensation compensation = compensator.compensation(
                        taskId, action.operationId(), action.tool(), readArgs(action.argsJson()));
                if (compensation == null) {
                    mark(action.id(), "FAILED");
                    done.add(action.tool() + ": no compensator");
                    continue;
                }
                compensation.undo().run();
                mark(action.id(), "DONE");
                done.add(label);
            } catch (RuntimeException failure) {
                mark(action.id(), "FAILED");
                done.add(action.tool() + ": FAILED");
                log.error("compensation.failed task={} tool={}", taskId, action.tool(), failure);
            }
        }
        log.info("compensation.done task={} actions={}", taskId, done);
        return done;
    }

    @Override
    public void clear(String taskId) {
        if (taskId != null) {
            jdbc.update("DELETE FROM ff_compensation WHERE task_id = ?", taskId);
        }
    }

    @Override
    public int size(String taskId) {
        if (taskId == null) {
            return 0;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ff_compensation WHERE task_id = ? AND status = 'PENDING'",
                Integer.class, taskId);
        return count == null ? 0 : count;
    }

    /**
     * Finishes compensations for tasks that already ended but still have PENDING rows —
     * the case a crashed worker leaves behind.
     */
    @Scheduled(fixedDelayString = "${forgeflow.compensation.scan-interval-ms:60000}")
    public void recoverStale() {
        List<String> taskIds = jdbc.queryForList("""
                        SELECT DISTINCT c.task_id
                          FROM ff_compensation c
                          JOIN ff_task t ON t.id = c.task_id
                         WHERE c.status = 'PENDING'
                           AND t.status IN ('FAILED', 'CANCELLED')
                           AND c.created_at < ?
                         LIMIT 50
                        """,
                String.class, Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))));
        for (String taskId : taskIds) {
            log.warn("compensation.recover task={} reason=task terminal with pending undo", taskId);
            compensateAll(taskId);
        }
    }

    private boolean claim(String id) {
        int rows = jdbc.update("""
                        UPDATE ff_compensation
                           SET status = 'RUNNING', updated_at = ?
                         WHERE id = ? AND status = 'PENDING'
                        """,
                Timestamp.from(Instant.now()), id);
        return rows == 1;
    }

    private void mark(String id, String status) {
        jdbc.update("""
                        UPDATE ff_compensation
                           SET status = ?, attempts = attempts + 1, updated_at = ?
                         WHERE id = ?
                        """,
                status, Timestamp.from(Instant.now()), id);
    }

    private String writeArgs(Map<String, Object> args) {
        try {
            return mapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot serialize compensation args", failure);
        }
    }

    private Map<String, Object> readArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, ARGS_TYPE);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read compensation args", failure);
        }
    }

    private record Pending(String id, String operationId, String tool, String argsJson,
                           String description) {
    }
}
