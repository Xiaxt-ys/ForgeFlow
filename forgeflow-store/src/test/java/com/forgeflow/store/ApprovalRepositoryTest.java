package com.forgeflow.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(ApprovalRepository.class)
@Sql(scripts = "/schema.sql")
class ApprovalRepositoryTest {

    @Autowired
    ApprovalRepository approvals;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ff_approval");
    }

    @Test
    void pendingApprovalCanBeFoundAndApprovedOnce() {
        approvals.insert("task-1", "turn-1", "call-1", "refund", "{\"amount\":1}", "{\"amount\":1}");

        ApprovalRecord pending = approvals.findPending("task-1").orElseThrow();
        assertThat(pending.tool()).isEqualTo("refund");
        assertThat(pending.turnId()).isEqualTo("turn-1");
        assertThat(pending.callId()).isEqualTo("call-1");

        assertThat(approvals.markApproved(pending.id())).isTrue();
        assertThat(approvals.markApproved(pending.id())).isFalse();
        assertThat(approvals.findPending("task-1")).isEmpty();
        assertThat(approvals.findApproved("task-1")).isPresent();
    }

    @Test
    void rejectionAndCleanup() {
        approvals.insert("task-2", "turn-2", "call-2", "drop", "{}", "{}");
        ApprovalRecord pending = approvals.findPending("task-2").orElseThrow();

        assertThat(approvals.markRejected(pending.id())).isTrue();
        assertThat(approvals.findApproved("task-2")).isEmpty();

        approvals.deleteByTask("task-2");
        assertThat(approvals.findByTask("task-2")).isEmpty();
    }
}
