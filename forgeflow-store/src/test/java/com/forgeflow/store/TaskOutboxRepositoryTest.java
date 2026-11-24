package com.forgeflow.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(TaskOutboxRepository.class)
@Sql(scripts = "/schema.sql")
class TaskOutboxRepositoryTest {

    @Autowired
    TaskOutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ff_outbox");
    }

    @Test
    void pendingRowCanBeMarkedSent() {
        outbox.insert("task-1", "trace-1");

        var pending = outbox.findPending(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).taskId()).isEqualTo("task-1");
        assertThat(pending.get(0).traceContext()).isEqualTo("trace-1");

        outbox.markSent(pending.get(0).id());
        assertThat(outbox.findPending(10)).isEmpty();
    }

    @Test
    void failedRowIsRetriedAfterBackoff() {
        outbox.insert("task-2", "trace-2");
        var row = outbox.findPending(10).get(0);

        outbox.markFailed(row.id(), 1, Instant.now().plusSeconds(30));
        assertThat(outbox.findPending(10)).isEmpty();
    }
}
