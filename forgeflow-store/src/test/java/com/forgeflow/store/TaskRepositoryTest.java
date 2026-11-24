package com.forgeflow.store;

import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Import(TaskRepository.class)
@Sql(scripts = "/schema.sql")
class TaskRepositoryTest {

    @Autowired
    TaskRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void claimUsesVersionAsFence() {
        TaskRecord task = task("t1", "idem-1");
        repository.insert(task);

        assertThat(repository.tryClaim("t1", 1L, "worker-a")).isTrue();
        assertThat(repository.tryClaim("t1", 1L, "worker-b")).isFalse();

        TaskRecord claimed = repository.getById("t1");
        assertThat(claimed.status()).isEqualTo(TaskStatus.CLAIMED);
        assertThat(claimed.version()).isEqualTo(2L);
        assertThat(claimed.workerId()).isEqualTo("worker-a");
    }

    @Test
    void heartbeatTouchKeepsRunningTasksFreshAndLeavesQueuedOnesAlone() {
        repository.insert(task("t9", "idem-9"));
        java.sql.Timestamp old = java.sql.Timestamp.from(Instant.now().minusSeconds(600));
        jdbc.update("UPDATE ff_task SET updated_at = ? WHERE id = 't9'", old);

        repository.touch("t9");
        assertThat(java.time.Duration.between(
                repository.getById("t9").updatedAt(), old.toInstant()).abs())
                .isLessThan(java.time.Duration.ofSeconds(1));

        repository.tryClaim("t9", 1L, "worker-a");
        repository.markRunning("t9", 2L);
        jdbc.update("UPDATE ff_task SET updated_at = ? WHERE id = 't9'", old);

        repository.touch("t9");
        assertThat(repository.getById("t9").updatedAt())
                .isAfter(Instant.now().minusSeconds(60));
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedByUniqueIndex() {
        repository.insert(task("t2", "same-key"));

        assertThatThrownBy(() -> repository.insert(task("t3", "same-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void successWriteIsFencedByVersion() {
        repository.insert(task("t4", "idem-4"));
        repository.tryClaim("t4", 1L, "worker-a");
        repository.markRunning("t4", 2L);

        assertThat(repository.markSucceeded("t4", 2L, "stale")).isFalse();
        assertThat(repository.markSucceeded("t4", 3L, "ok")).isTrue();
        assertThat(repository.getById("t4").result()).isEqualTo("ok");
    }

    @Test
    void staleTasksCanBeReclaimed() {
        repository.insert(task("t5", "idem-5"));
        repository.tryClaim("t5", 1L, "worker-a");
        repository.markRunning("t5", 2L);

        assertThat(repository.tryReclaim("t5", 3L, "worker-b")).isTrue();
        TaskRecord reclaimed = repository.getById("t5");
        assertThat(reclaimed.status()).isEqualTo(TaskStatus.CLAIMED);
        assertThat(reclaimed.workerId()).isEqualTo("worker-b");
    }

    @Test
    void queuedTaskCancelsDirectly() {
        repository.insert(task("t6", "idem-6"));
        assertThat(repository.markCancelling("t6")).isTrue();
        assertThat(repository.getById("t6").status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void runningTaskMovesToCancelling() {
        repository.insert(task("t7", "idem-7"));
        repository.tryClaim("t7", 1L, "worker-a");
        repository.markRunning("t7", 2L);

        assertThat(repository.markCancelling("t7")).isTrue();
        assertThat(repository.getById("t7").status()).isEqualTo(TaskStatus.CANCELLING);
    }

    @Test
    void waitingForHumanCanBeResumedWithPromptUpdate() {
        repository.insert(task("t3", "idem-3"));
        assertThat(repository.tryClaim("t3", 1L, "worker-a")).isTrue();
        assertThat(repository.markRunning("t3", 2L)).isTrue();
        assertThat(repository.markWaitingHuman("t3", 3L, "output invalid")).isTrue();

        TaskRecord waiting = repository.getById("t3");
        assertThat(waiting.status()).isEqualTo(TaskStatus.WAITING_HUMAN);
        assertThat(waiting.version()).isEqualTo(4L);

        // A stale version must not be able to resume.
        assertThat(repository.resumeFromHuman("t3", 3L, "ignored")).isFalse();
        assertThat(repository.resumeFromHuman("t3", 4L, "hello [human] retry")).isTrue();

        TaskRecord resumed = repository.getById("t3");
        assertThat(resumed.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(resumed.prompt()).isEqualTo("hello [human] retry");
        assertThat(resumed.version()).isEqualTo(5L);
    }

    @Test
    void waitingForHumanCanBeRejected() {
        repository.insert(task("t4", "idem-4"));
        assertThat(repository.tryClaim("t4", 1L, "worker-a")).isTrue();
        assertThat(repository.markRunning("t4", 2L)).isTrue();
        assertThat(repository.markWaitingHuman("t4", 3L, "output invalid")).isTrue();

        assertThat(repository.markRejectedByHuman("t4", 4L, "cannot fix")).isTrue();

        TaskRecord failed = repository.getById("t4");
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("HUMAN_REJECTED");
    }

    @Test
    void waitingTaskBecomesStaleForTheTimeoutScan() {
        repository.insert(task("t5", "idem-5"));
        assertThat(repository.tryClaim("t5", 1L, "worker-a")).isTrue();
        assertThat(repository.markRunning("t5", 2L)).isTrue();
        assertThat(repository.markWaitingHuman("t5", 3L, "output invalid")).isTrue();
        jdbc.update("UPDATE ff_task SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), "t5");

        assertThat(repository.findStale(List.of(TaskStatus.WAITING_HUMAN),
                Instant.now().minusSeconds(60), 10))
                .extracting(TaskRecord::id)
                .containsExactly("t5");
    }

    @Test
    void waitingApprovalCanBeResumedOrRejected() {
        repository.insert(task("t6", "idem-6"));
        assertThat(repository.tryClaim("t6", 1L, "worker-a")).isTrue();
        assertThat(repository.markRunning("t6", 2L)).isTrue();
        assertThat(repository.markWaitingApproval("t6", 3L, "refund needs approval")).isTrue();
        assertThat(repository.getById("t6").status()).isEqualTo(TaskStatus.WAITING_APPROVAL);

        // Approve path: back to the queue at a new version.
        assertThat(repository.resumeFromApproval("t6", 3L)).isFalse();
        assertThat(repository.resumeFromApproval("t6", 4L)).isTrue();
        TaskRecord queued = repository.getById("t6");
        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.version()).isEqualTo(5L);

        // Reject path on a second suspension.
        assertThat(repository.tryClaim("t6", 5L, "worker-a")).isTrue();
        assertThat(repository.markRunning("t6", 6L)).isTrue();
        assertThat(repository.markWaitingApproval("t6", 7L, "again")).isTrue();
        assertThat(repository.markApprovalRejected("t6", 8L, "no")).isTrue();
        TaskRecord rejected = repository.getById("t6");
        assertThat(rejected.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(rejected.errorCode()).isEqualTo("APPROVAL_REJECTED");
    }

    private TaskRecord task(String id, String idempotencyKey) {
        Instant now = Instant.now();
        return new TaskRecord(id, "tenant-1", TaskStatus.QUEUED, 1L, null,
                idempotencyKey, "hello", null, null, null, now, now);
    }
}
